// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.logging.LogUtils;
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
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
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

        GameRules gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        GameRuleResolution gameRuleResolution = applyGameRules(gameRules, worldOutput);

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        // Cheats are the world-defaults master's to impose; with it off cheats fall back to off here.
        boolean allowCommands = worldOutput.overrideWorldDefaults() && worldOutput.allowCommands();
        LevelSettings settings = new LevelSettings(
                levelName, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, allowCommands,
                WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = worldOptions(worldType, worldOutput);
        WorldGenSettings worldGenSettings = new WorldGenSettings(
                worldOptions, new WorldDimensions(registries.lookupOrThrow(Registries.LEVEL_STEM)));

        PrimaryLevelData worldData = new PrimaryLevelData(
                settings, dimensions.specialWorldProperty(), dimensions.lifecycle());
        // Vanilla treats a level.dat whose initialized flag is false as new and replaces the spawn on first open.
        worldData.setInitialized(true);
        return new LevelData(worldData, registries, gameRuleResolution, worldGenSettings, gameRules);
    }

    @Override
    public void warmWorldgen() {
        VanillaWorldgenRegistries.get();
    }

    private static GameRuleResolution applyGameRules(GameRules gameRules, WorldOutputConfig worldOutput) {
        Map<String, GameRule<?>> available = availableRulesById(gameRules);
        GameRuleSchema schema = new GameRuleSchema() {
            @Override
            public boolean hasRule(String id) {
                return available.containsKey(id);
            }

            @Override
            public boolean acceptsValue(String id, String rawValue) {
                GameRule<?> rule = available.get(id);
                return rule != null && rule.deserialize(rawValue).result().isPresent();
            }
        };
        GameRuleResolution resolution = worldOutput.resolveGameRules(curatedByBandId(), schema);
        for (Map.Entry<String, String> rule : resolution.effective().entrySet()) {
            GameRule<?> gameRule = available.get(rule.getKey());
            if (gameRule != null) {
                setRule(gameRules, gameRule, rule.getValue());
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

    private static <T> void setRule(GameRules gameRules, GameRule<T> rule, String rawValue) {
        rule.deserialize(rawValue).result().ifPresent(value -> gameRules.set(rule, value, null));
    }

    private static Map<String, GameRule<?>> availableRulesById(GameRules gameRules) {
        Map<String, GameRule<?>> byId = new HashMap<>();
        gameRules.availableRules().forEach(rule -> byId.put(rule.id(), rule));
        return byId;
    }

    @Override
    public List<CuratedGameRule> curatedGameRules() {
        Map<String, GameRule<?>> byId = availableRulesById(new GameRules(FeatureFlags.DEFAULT_FLAGS));
        List<CuratedGameRule> rules = new ArrayList<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            GameRule<?> rule = byId.get(spec.bandId());
            if (rule == null) {
                continue;
            }
            if (rule.valueClass() == Boolean.class) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(),
                        defaultRuleValue(rule), "0"));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    private static <T> String defaultRuleValue(GameRule<T> rule) {
        return rule.serialize(rule.defaultValue());
    }

    private static List<CuratedSpec> buildCuratedGameRules() {
        List<CuratedSpec> curated = new ArrayList<>();
        curated.add(new CuratedSpec("spawn_mobs", "spawn_mobs", "false"));
        curated.add(new CuratedSpec("fire_spread_radius_around_player", "fire_spread_radius_around_player", "0"));
        curated.add(new CuratedSpec("spread_vines", "spread_vines", "false"));
        curated.add(new CuratedSpec("advance_time", "advance_time", "false"));
        curated.add(new CuratedSpec("advance_weather", "advance_weather", "false"));
        curated.add(new CuratedSpec("keep_inventory", "keep_inventory", "true"));
        curated.add(new CuratedSpec("mob_griefing", "mob_griefing", "false"));
        curated.add(new CuratedSpec("spawn_wardens", "spawn_wardens", "false"));
        curated.add(new CuratedSpec("spawn_wandering_traders", "spawn_wandering_traders", "false"));
        curated.add(new CuratedSpec("spawn_patrols", "spawn_patrols", "false"));
        return Collections.unmodifiableList(curated);
    }

    @Override
    public void save(LevelStorageSource.LevelStorageAccess access, LevelData data,
            @Nullable CapturedPlayer player) {
        Path saveRoot = access.getLevelDirectory().path();
        WorldData worldData = data.worldData();
        RegistryAccess registries = data.registries();

        // The save-side files (worldgen, game rules, clocks) and the player file are all written BEFORE level.dat, so
        // any IO failure aborts before level.dat is written: an aborted save with no level.dat is invisible in
        // vanilla's world list, whereas a level.dat with a missing world_gen_settings.dat opens as a random-seed world
        // around the captured chunks. Carried from the folder's existing level.dat when this finish has no player of
        // its own: readPriorPlayer resolves the player file through this stamp and through nothing else, so dropping it
        // silently strands a player record this save still holds.
        UUID singleplayerUuid = player == null ? priorSingleplayerUuid(saveRoot) : null;
        if (player != null) {
            PrimaryLevelData levelData = (PrimaryLevelData) worldData;
            levelData.setGameType(player.gameType());
            levelData.setSpawn(RespawnData.of(player.dimension(), player.spawnPos(), player.yaw(), player.pitch()));
            levelData.setDifficulty(player.difficulty());
            singleplayerUuid = writePlayerData(saveRoot, player.playerTag());
        }
        writeMetadata(saveRoot, registries, worldData, data.worldGenSettings(), data.gameRules());
        access.saveDataTag(worldData, singleplayerUuid);
    }

    /**
     * Fail-soft on every read failure: the {@code singleplayer_uuid} stamp is a carry-forward handle, and losing it
     * costs a later resume its prior contents, which is strictly better than failing a save that is otherwise written.
     */
    private static @Nullable UUID priorSingleplayerUuid(Path saveRoot) {
        Path levelDatFile = saveRoot.resolve("level.dat");
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDatFile, NbtAccounter.uncompressedQuota());
            return root.get("Data") instanceof CompoundTag data
                    ? data.read("singleplayer_uuid", UUIDUtil.CODEC).orElse(null)
                    : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not read the prior singleplayer uuid from {}; a later resume will not find this "
                    + "folder's player record and will not carry its ender chest or parked mount forward",
                    levelDatFile, e);
            return null;
        }
    }

    private static UUID writePlayerData(Path saveRoot, CompoundTag playerTag) {
        UUID uuid = playerTag.read("UUID", UUIDUtil.CODEC).orElseThrow(
                () -> new IllegalStateException("captured player tag carries no UUID; cannot place players/data"));
        Path file = saveRoot.resolve("players").resolve("data").resolve(uuid + ".dat");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(playerTag, file);
        } catch (IOException e) {
            throw new UncheckedIOException("aborting the save: failed to write the player data " + file, e);
        }
        return uuid;
    }

    /**
     * Any {@code IOException} aborts the save unchecked rather than catch-and-degrade: a swallowed worldgen write can
     * leave level.dat beside a missing world_gen_settings.dat, which opens as a random-seed world, and a missing clocks
     * file opens the world at tick 0, not noon.
     */
    private void writeMetadata(Path saveRoot, RegistryAccess registries, WorldData worldData,
            WorldGenSettings worldGenSettings, GameRules gameRules) {
        try {
            LevelStorageSource.writeWorldGenSettings(registries, saveRoot, worldGenSettings);
            LevelStorageSource.writeGameRules(worldData, saveRoot, gameRules);
            writeWorldClocks(saveRoot, registries);
        } catch (IOException e) {
            throw new UncheckedIOException("aborting the save: level metadata write failed", e);
        }
    }

    private static void writeWorldClocks(Path saveRoot, RegistryAccess registries) throws IOException {
        Holder<WorldClock> overworld = registries.lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(WorldClocks.OVERWORLD);
        PackedClockStates clocks = new PackedClockStates(Map.of(overworld, new ClockState(NOON, 0.0F, 1.0F, false)));
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Tag encoded = PackedClockStates.CODEC.encodeStart(ops, clocks).getOrThrow();
        CompoundTag envelope = new CompoundTag();
        envelope.put("data", encoded);
        NbtUtils.addCurrentDataVersion(envelope);
        Path file = saveRoot.resolve("data").resolve("minecraft").resolve("world_clocks.dat");
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(envelope, file);
    }

    @Override
    public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDatFile, NbtAccounter.uncompressedQuota());
            if (!(root.get("Data") instanceof CompoundTag data)) {
                return null;
            }
            UUID uuid = data.read("singleplayer_uuid", UUIDUtil.CODEC).orElse(null);
            Path saveRoot = levelDatFile.getParent();
            if (uuid == null || saveRoot == null) {
                return null;
            }
            Path playerFile = saveRoot.resolve("players").resolve("data").resolve(uuid + ".dat");
            return Files.exists(playerFile) ? NbtIo.readCompressed(playerFile, NbtAccounter.uncompressedQuota()) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the prior player data " + levelDatFile, e);
        }
    }

    private static WorldDimensions.Complete bakedDimensions(WorldType worldType, RegistryAccess registries) {
        return switch (worldType) {
            case DEFAULT -> WorldPresets.createNormalWorldDimensions(registries).bake(emptyLevelStems());
            case FLAT -> WorldPresets.createFlatWorldDimensions(registries).bake(emptyLevelStems());
            case VOID -> voidDimensions(registries);
        };
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
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        ResourceKey<Biome> biomeKey = biomes.containsKey(Biomes.THE_VOID) ? Biomes.THE_VOID : Biomes.PLAINS;
        Holder<Biome> voidBiome = biomes.getOrThrow(biomeKey);

        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.<StructureSet>empty()),
                voidBiome, List.of());
        flat.updateLayers();

        Registry<DimensionType> dimensionTypes = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        Map<ResourceKey<LevelStem>, LevelStem> stems = Map.of(
                LevelStem.OVERWORLD, voidStem(dimensionTypes, BuiltinDimensionTypes.OVERWORLD, flat),
                LevelStem.NETHER, voidStem(dimensionTypes, BuiltinDimensionTypes.NETHER, flat),
                LevelStem.END, voidStem(dimensionTypes, BuiltinDimensionTypes.END, flat));

        return new WorldDimensions(stems).bake(emptyLevelStems());
    }

    private static LevelStem voidStem(
            Registry<DimensionType> dimensionTypes, ResourceKey<DimensionType> type, FlatLevelGeneratorSettings flat) {
        return new LevelStem(dimensionTypes.getOrThrow(type), new FlatLevelSource(flat));
    }
}
