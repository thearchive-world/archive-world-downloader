// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.CapturedPlayer;
import world.thearchive.wdl.adapter.LevelDataWriter;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.GameRuleSchema;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.core.WorldType;

public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LEVEL_NAME = "Archive World Downloader";

    private static final long PLACEHOLDER_SEED = 0L;

    private static final long NOON = 6000L;

    private static final List<CuratedSpec> CURATED_GAME_RULES = buildCuratedGameRules();

    private record CuratedSpec(String wdlId, String bandId, String curatedValue) {}

    @SuppressWarnings("deprecation")
    @Override
    public LevelData buildLevelData(RegistryAccess clientRegistries, WorldOutputConfig worldOutput,
            @Nullable String worldName) {
        WorldType worldType = worldOutput.worldType();
        // A real generator needs the full vanilla worldgen registries, which a multiplayer client is never sent. The
        // dimensions and the encode context must share one registry set (their generator holders must be owned by the
        // registries the encode resolves against), so both derive here. WorldgenWarmup warms off-thread on the same
        // predicate, so widening this condition alone puts a reconstruction on the render thread.
        RegistryAccess generatorRegistries = worldType.generatesTerrain()
                ? VanillaWorldgenRegistries.get()
                : clientRegistries;
        WorldDimensions.Complete dimensions = bakedDimensions(worldType, generatorRegistries);
        RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(
                Stream.concat(generatorRegistries.registries(), dimensions.dimensionsRegistryAccess().registries()))
                        .freeze();

        GameRules gameRules = new GameRules();
        GameRuleResolution gameRuleResolution = applyGameRules(gameRules, worldOutput);

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        // Cheats are the world-defaults master's to impose; with it off cheats fall back to off here.
        boolean allowCommands = worldOutput.overrideWorldDefaults() && worldOutput.allowCommands();
        LevelSettings settings = new LevelSettings(
                levelName, GameType.SURVIVAL, false, Difficulty.NORMAL, allowCommands,
                gameRules, WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = worldOptions(worldType, worldOutput);

        // Fail loud: PrimaryLevelData.setTagData silently omits or partially writes WorldGenSettings if its encode
        // errors, yielding an unopenable world.
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<Tag> worldGen = WorldGenSettings.encode(ops, worldOptions, registries);
        if (worldGen.error().isPresent()) {
            throw new IllegalStateException(
                    "level.dat WorldGenSettings encode failed (world would be unopenable): "
                            + worldGen.error().get().message());
        }

        PrimaryLevelData worldData = new PrimaryLevelData(
                settings, worldOptions, dimensions.specialWorldProperty(), dimensions.lifecycle());
        // Vanilla treats a level.dat whose initialized flag is false as new and replaces the spawn on first open.
        worldData.setInitialized(true);
        worldData.setDayTime(NOON);
        return new LevelData(worldData, registries, gameRuleResolution);
    }

    @Override
    public void warmWorldgen() {
        VanillaWorldgenRegistries.get();
    }

    private static GameRuleResolution applyGameRules(GameRules gameRules, WorldOutputConfig worldOutput) {
        Map<String, GameRules.Value<?>> available = availableRulesById(gameRules);
        GameRuleSchema schema = new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return available.containsKey(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                GameRules.Value<?> rule = available.get(id);
                return rule != null && valueParses(rule, rawValue);
            }
        };
        GameRuleResolution resolution = worldOutput.resolveGameRules(curatedByBandId(), schema);
        for (Map.Entry<String, String> rule : resolution.effective().entrySet()) {
            GameRules.Value<?> value = available.get(rule.getKey());
            if (value != null) {
                setRule(value, rule.getValue());
            }
        }
        for (String id : resolution.droppedInvalidValues()) {
            LOGGER.warn("ignoring game-rule override gamerule.{}: its value does not parse for this rule", id);
        }
        for (String id : resolution.unknownIds()) {
            LOGGER.warn("ignoring game-rule override gamerule.{}: no such game rule on this Minecraft version", id);
        }
        return resolution;
    }

    private static Map<String, String> curatedByBandId() {
        Map<String, String> byBandId = new LinkedHashMap<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            byBandId.put(spec.bandId(), spec.curatedValue());
        }
        return byBandId;
    }

    private static boolean valueParses(GameRules.Value<?> rule, String rawValue) {
        if (rule instanceof GameRules.BooleanValue) {
            return "true".equals(rawValue) || "false".equals(rawValue);
        }
        if (rule instanceof GameRules.IntegerValue) {
            try {
                Integer.parseInt(rawValue);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static void setRule(GameRules.Value<?> rule, String rawValue) {
        if (rule instanceof GameRules.BooleanValue booleanValue) {
            booleanValue.set(Boolean.parseBoolean(rawValue), null);
        } else if (rule instanceof GameRules.IntegerValue integerValue) {
            integerValue.tryDeserialize(rawValue);
        }
    }

    private static Map<String, GameRules.Value<?>> availableRulesById(GameRules gameRules) {
        Map<String, GameRules.Value<?>> byId = new HashMap<>();
        gameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                byId.put(key.getId(), gameRules.getRule(key));
            }
        });
        return byId;
    }

    @Override
    public List<CuratedGameRule> curatedGameRules() {
        Map<String, GameRules.Value<?>> byId = availableRulesById(new GameRules());
        List<CuratedGameRule> rules = new ArrayList<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            GameRules.Value<?> rule = byId.get(spec.bandId());
            if (rule == null) {
                continue;
            }
            if (rule instanceof GameRules.BooleanValue) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), rule.serialize(), "0"));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    // The fire-spread row is named for the newer integer radius rule; the boolean doFireTick is this band's equivalent.
    private static List<CuratedSpec> buildCuratedGameRules() {
        List<CuratedSpec> curated = new ArrayList<>();
        curated.add(new CuratedSpec("spawn_mobs", "doMobSpawning", "false"));
        curated.add(new CuratedSpec("fire_spread_radius_around_player", "doFireTick", "false"));
        curated.add(new CuratedSpec("spread_vines", "doVinesSpread", "false"));
        curated.add(new CuratedSpec("advance_time", "doDaylightCycle", "false"));
        curated.add(new CuratedSpec("advance_weather", "doWeatherCycle", "false"));
        curated.add(new CuratedSpec("keep_inventory", "keepInventory", "true"));
        curated.add(new CuratedSpec("mob_griefing", "mobGriefing", "false"));
        curated.add(new CuratedSpec("spawn_wardens", "doWardenSpawning", "false"));
        curated.add(new CuratedSpec("spawn_wandering_traders", "doTraderSpawning", "false"));
        curated.add(new CuratedSpec("spawn_patrols", "doPatrolSpawning", "false"));
        return Collections.unmodifiableList(curated);
    }

    @Override
    public void save(LevelStorageSource.LevelStorageAccess access, LevelData data,
            @Nullable CapturedPlayer player) {
        if (player == null) {
            // level.dat's "Player" is the only copy of the player record at this band and this write carries
            // none, so writing unconditionally destroys a prior download's record, silently.
            if (!Files.exists(access.getLevelPath(LevelResource.LEVEL_DATA_FILE))) {
                access.saveDataTag(data.registries(), data.worldData());
            }
            return;
        }
        PrimaryLevelData levelData = (PrimaryLevelData) data.worldData();
        levelData.setGameType(player.gameType());
        levelData.setSpawn(player.spawnPos(), player.yaw());
        levelData.setDifficulty(player.difficulty());
        access.saveDataTag(data.registries(), data.worldData(), player.playerTag());
    }

    @Override
    public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDatFile, NbtAccounter.unlimitedHeap());
            return root.get("Data") instanceof CompoundTag data && data.get("Player") instanceof CompoundTag player
                    ? player
                    : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the prior player data " + levelDatFile, e);
        }
    }

    private static WorldDimensions.Complete bakedDimensions(WorldType worldType, RegistryAccess registries) {
        return switch (worldType) {
            case DEFAULT -> WorldPresets.createNormalWorldDimensions(registries).bake(emptyLevelStems());
            case FLAT -> flatWorldDimensions(registries).bake(emptyLevelStems());
            case VOID -> voidDimensions(registries);
        };
    }

    private static WorldDimensions flatWorldDimensions(RegistryAccess registries) {
        return registries.registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
    }

    private static WorldOptions worldOptions(WorldType worldType, WorldOutputConfig worldOutput) {
        if (worldType == WorldType.VOID) {
            return new WorldOptions(PLACEHOLDER_SEED, false, false);
        }
        return new WorldOptions(worldOutput.worldSeed(), worldOutput.generateFeatures(), false);
    }

    private static Registry<LevelStem> emptyLevelStems() {
        return new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
    }

    private static WorldDimensions.Complete voidDimensions(RegistryAccess registries) {
        Registry<Biome> biomes = registries.registryOrThrow(Registries.BIOME);
        ResourceKey<Biome> biomeKey = biomes.containsKey(Biomes.THE_VOID) ? Biomes.THE_VOID : Biomes.PLAINS;
        Holder<Biome> voidBiome = biomes.getHolderOrThrow(biomeKey);

        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.<StructureSet>empty()),
                voidBiome, List.of());
        flat.updateLayers();

        Registry<DimensionType> dimensionTypes = registries.registryOrThrow(Registries.DIMENSION_TYPE);
        Map<ResourceKey<LevelStem>, LevelStem> stems = Map.of(
                LevelStem.OVERWORLD, voidStem(dimensionTypes, BuiltinDimensionTypes.OVERWORLD, flat),
                LevelStem.NETHER, voidStem(dimensionTypes, BuiltinDimensionTypes.NETHER, flat),
                LevelStem.END, voidStem(dimensionTypes, BuiltinDimensionTypes.END, flat));

        return new WorldDimensions(stems).bake(emptyLevelStems());
    }

    private static LevelStem voidStem(
            Registry<DimensionType> dimensionTypes, ResourceKey<DimensionType> type, FlatLevelGeneratorSettings flat) {
        return new LevelStem(dimensionTypes.getHolderOrThrow(type), new FlatLevelSource(flat));
    }
}
