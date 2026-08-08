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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.CapturedPlayer;
import world.thearchive.wdl.adapter.LevelDataWriter;
import world.thearchive.wdl.core.CuratedGameRule;
import world.thearchive.wdl.core.GameRuleResolution;
import world.thearchive.wdl.core.GameRuleSchema;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.core.WorldType;

/**
 * 1.21.11 {@code level.dat} writer for the selected generator: the default superflat VOID (all air, built from the
 * client's synced {@code BIOME} + {@code DIMENSION_TYPE} registries), or the vanilla DEFAULT/FLAT presets (built from
 * the reconstructed worldgen registries in {@link VanillaWorldgenRegistries}). The captured chunks always supply the
 * real terrain; the generator only fills the un-captured gaps, which for DEFAULT/FLAT are freshly generated and not the
 * server's actual land (the server's seed is not recoverable from a client).
 */
public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LEVEL_NAME = "Archive World Downloader";

    /** Server seed is unknown (not transmitted); irrelevant for void gen (no terrain is generated). */
    private static final long PLACEHOLDER_SEED = 0L;

    /** 24000-tick day; 6000 is noon. */
    private static final long NOON = 6000L;

    /**
     * The curated safe set for the 1.21.11 band, by snake_case rule id to raw value. Fire is the integer
     * fire_spread_radius_around_player=0 here (the boolean doFireTick); the rest are booleans. The user's gamerule.*
     * overrides are validated and applied on top of this (see {@link WorldOutputConfig}).
     */
    private static final Map<String, String> CURATED_GAME_RULES = buildCuratedGameRules();

    // SpecialWorldProperty is vanilla-deprecated, but the only public PrimaryLevelData ctor still
    // requires it; we take it from the baked dimensions (FLAT, for this superflat void world).
    @SuppressWarnings("deprecation")
    @Override
    public LevelData buildLevelData(RegistryAccess clientRegistries, WorldOutputConfig worldOutput,
            @Nullable String worldName) {
        WorldType worldType = worldOutput.worldType();
        // A real generator needs the full vanilla worldgen registries, which a multiplayer client is never sent;
        // the void generator only needs the client's synced biome/dimension-type registries, so it stays on them
        // and its output is unchanged. The dimensions and the encode context must share one registry set (their
        // generator holders must be owned by the registries the encode resolves against), so both derive here.
        RegistryAccess generatorRegistries = worldType.needsWorldgenReconstruction()
                ? VanillaWorldgenRegistries.get()
                : clientRegistries;
        WorldDimensions.Complete dimensions = bakedDimensions(worldType, generatorRegistries);
        RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(
                Stream.concat(generatorRegistries.registries(), dimensions.dimensionsRegistryAccess().registries()))
                        .freeze();

        GameRules gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        GameRuleResolution gameRuleResolution = applyGameRules(gameRules, worldOutput);

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        // Cheats and game mode are the world-defaults master's to impose; with it off the world opens vanilla,
        // so cheats fall back to off here. Noon is a fixed invariant, applied unconditionally below.
        boolean allowCommands = worldOutput.overrideWorldDefaults() && worldOutput.allowCommands();
        LevelSettings settings = new LevelSettings(
                levelName, GameType.SURVIVAL, false, Difficulty.NORMAL, allowCommands,
                gameRules, WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = worldOptions(worldType, worldOutput);

        // Fail loud: PrimaryLevelData.setTagData silently omits WorldGenSettings if its encode errors,
        // yielding an unopenable world. Pre-encode and reject any error/partial result first.
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<Tag> worldGen = WorldGenSettings.encode(ops, worldOptions, registries);
        if (worldGen.error().isPresent()) {
            throw new IllegalStateException(
                    "level.dat WorldGenSettings encode failed (world would be unopenable): "
                            + worldGen.error().get().message());
        }

        PrimaryLevelData worldData = new PrimaryLevelData(
                settings, worldOptions, dimensions.specialWorldProperty(), dimensions.lifecycle());
        // Downloaded worlds always open at noon, a fixed world-open invariant. A fresh PrimaryLevelData already
        // opens clear (raining, thundering, and their timers default off), so weather needs no write here.
        worldData.setDayTime(NOON);
        return new LevelData(worldData, registries, gameRuleResolution);
    }

    @Override
    public void warmWorldgen() {
        VanillaWorldgenRegistries.get();
    }

    /**
     * Resolve the curated set with the user's overrides against this band's live rules, apply the effective rules to
     * {@code gameRules} via the offline {@code set(.., null)} write, and log the dropped/unknown diagnostics. Returns
     * the resolution.
     */
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
        GameRuleResolution resolution = worldOutput.resolveGameRules(CURATED_GAME_RULES, schema);
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

    /** Set one validated rule on the offline GameRules (no server, so the change-callback is skipped). */
    private static <T> void setRule(GameRules gameRules, GameRule<T> rule, String rawValue) {
        rule.deserialize(rawValue).result().ifPresent(value -> gameRules.set(rule, value, null));
    }

    /** Index the rules available at this band (feature-filtered) by their short id, for lookup and validation. */
    private static Map<String, GameRule<?>> availableRulesById(GameRules gameRules) {
        Map<String, GameRule<?>> byId = new HashMap<>();
        gameRules.availableRules().forEach(rule -> byId.put(rule.id(), rule));
        return byId;
    }

    @Override
    public List<CuratedGameRule> curatedGameRules() {
        Map<String, GameRule<?>> byId = availableRulesById(new GameRules(FeatureFlags.DEFAULT_FLAGS));
        List<CuratedGameRule> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : CURATED_GAME_RULES.entrySet()) {
            String id = entry.getKey();
            String curated = entry.getValue();
            GameRule<?> rule = byId.get(id);
            if (rule != null && rule.valueClass() == Boolean.class) {
                rules.add(new CuratedGameRule(id, curated, "true", "false"));
            } else if (rule != null) {
                rules.add(new CuratedGameRule(id, curated, defaultRuleValue(rule), "0"));
            } else {
                // A curated id absent at this band cannot toggle; degrade to a fixed row rather than a null cell.
                rules.add(new CuratedGameRule(id, curated, curated, curated));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    /** The rule's band default rendered to its raw string, for an integer rule's enabled (on) toggle position. */
    private static <T> String defaultRuleValue(GameRule<T> rule) {
        return rule.serialize(rule.defaultValue());
    }

    private static Map<String, String> buildCuratedGameRules() {
        Map<String, String> curated = new LinkedHashMap<>();
        curated.put("spawn_mobs", "false");
        curated.put("fire_spread_radius_around_player", "0");
        curated.put("spread_vines", "false");
        curated.put("advance_time", "false");
        curated.put("advance_weather", "false");
        curated.put("keep_inventory", "true");
        curated.put("mob_griefing", "false");
        curated.put("spawn_wardens", "false");
        curated.put("spawn_wandering_traders", "false");
        curated.put("spawn_patrols", "false");
        return Collections.unmodifiableMap(curated);
    }

    @Override
    public void save(LevelStorageSource.LevelStorageAccess access, LevelData data,
            @Nullable CapturedPlayer player) {
        // Do not port to 26.x by picking the nearest saveDataTag overload: no overload there carries a player
        // compound, so that band must write players/data/<uuid>.dat itself.
        if (player == null) {
            access.saveDataTag(data.registries(), data.worldData());
            return;
        }
        // buildLevelData always produces a PrimaryLevelData; the setters flip the fields createTag reads.
        PrimaryLevelData levelData = (PrimaryLevelData) data.worldData();
        levelData.setGameType(player.gameType());
        levelData.setSpawn(RespawnData.of(player.dimension(), player.spawnPos(), player.yaw(), player.pitch()));
        levelData.setDifficulty(player.difficulty());
        // The 3-argument saveDataTag routes the captured tag into createTag's "Player" slot.
        access.saveDataTag(data.registries(), data.worldData(), player.playerTag());
    }

    @Override
    public @Nullable CompoundTag readPriorPlayer(Path levelDatFile) {
        if (!Files.exists(levelDatFile)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDatFile, NbtAccounter.uncompressedQuota());
            return root.get("Data") instanceof CompoundTag data && data.get("Player") instanceof CompoundTag player
                    ? player
                    : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the prior player data " + levelDatFile, e);
        }
    }

    /**
     * The baked dimensions for the chosen generator: the vanilla NORMAL/FLAT presets (real terrain, built from the same
     * worldgen registries the encode resolves against) or the hardcoded superflat void. The captured chunks always
     * supply the real terrain; the generator only governs the un-captured surroundings, which for DEFAULT/FLAT are
     * freshly generated and not the server's actual land (the server seed is not recoverable).
     */
    private static WorldDimensions.Complete bakedDimensions(WorldType worldType, RegistryAccess registries) {
        return switch (worldType) {
            case DEFAULT -> WorldPresets.createNormalWorldDimensions(registries).bake(emptyLevelStems());
            case FLAT -> WorldPresets.createFlatWorldDimensions(registries).bake(emptyLevelStems());
            case VOID -> voidDimensions(registries);
        };
    }

    private static WorldOptions worldOptions(WorldType worldType, WorldOutputConfig worldOutput) {
        if (worldType == WorldType.VOID) {
            return new WorldOptions(PLACEHOLDER_SEED, false, false); // no structures for void
        }
        return new WorldOptions(worldOutput.worldSeed(), worldOutput.generateFeatures(), false);
    }

    private static Registry<LevelStem> emptyLevelStems() {
        return new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
    }

    /** The three vanilla dimensions, each a void superflat generator, baked into a LEVEL_STEM set. */
    private static WorldDimensions.Complete voidDimensions(RegistryAccess registries) {
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        ResourceKey<Biome> biomeKey = biomes.containsKey(Biomes.THE_VOID) ? Biomes.THE_VOID : Biomes.PLAINS;
        Holder<Biome> voidBiome = biomes.getOrThrow(biomeKey);

        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.<StructureSet>empty()),
                voidBiome, List.of());
        flat.updateLayers(); // no layers -> all air (voidSettings)

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
