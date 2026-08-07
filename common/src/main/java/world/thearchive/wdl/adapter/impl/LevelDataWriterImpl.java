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

/**
 * 26.1.2 {@code level.dat} writer for the selected generator: the default superflat VOID (all air, built from the
 * client's synced {@code BIOME} + {@code DIMENSION_TYPE} registries), or the vanilla DEFAULT/FLAT presets (built from
 * the reconstructed worldgen registries in {@link VanillaWorldgenRegistries}). The captured chunks always supply the
 * real terrain; the generator only fills the un-captured gaps, which for DEFAULT/FLAT are freshly generated and not the
 * server's actual land (the server's seed is not recoverable from a client).
 *
 * <p>At 26.x the world metadata no longer lives in one {@code level.dat}: worldgen, the game rules and the world clocks
 * are their own namespaced {@code data/minecraft/*.dat} SavedData files, and the captured player is a
 * {@code players/data/<uuid>.dat} rather than a {@code level.dat "Player"} compound.
 */
public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LEVEL_NAME = "Archive World Downloader";

    /** Server seed is unknown (not transmitted); irrelevant for void gen (no terrain is generated). */
    private static final long PLACEHOLDER_SEED = 0L;

    /** 24000-tick day; 6000 is noon. */
    private static final long NOON = 6000L;

    /**
     * The curated safe set: each rule's stable WDL name, the running band's own id for it, and the curated raw value.
     * The WDL name is what the menu and the lang catalogs bind (band-stable); the band id is what the download writes.
     * Fire is the integer fire_spread_radius_around_player=0 here; the rest are booleans. The user's gamerule.*
     * overrides, keyed by band id, are validated and applied on top of this (see {@link WorldOutputConfig}).
     */
    private static final List<CuratedSpec> CURATED_GAME_RULES = buildCuratedGameRules();

    /** One curated rule: its stable WDL name, the running band's id for it, and the curated raw value. */
    private record CuratedSpec(String wdlId, String bandId, String curatedValue) {}

    // buildLevelData -> save bridge: the worldgen and game-rule values the save-side SavedData writes need, which the
    // 26.x WorldData no longer carries (worldGenOptions() and getGameRules() are both gone). Set by buildLevelData,
    // consumed and cleared by the paired save; never spans two downloads.
    private @Nullable WorldGenSettings pendingWorldGenSettings;
    private @Nullable GameRules pendingGameRules;

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
        // so cheats fall back to off here. Noon is a fixed invariant, written into world_clocks.dat by save().
        boolean allowCommands = worldOutput.overrideWorldDefaults() && worldOutput.allowCommands();
        LevelSettings settings = new LevelSettings(
                levelName, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, allowCommands,
                WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = worldOptions(worldType, worldOutput);
        WorldGenSettings worldGenSettings = new WorldGenSettings(
                worldOptions, new WorldDimensions(registries.lookupOrThrow(Registries.LEVEL_STEM)));

        // A fresh PrimaryLevelData opens clear (raining, thundering and their timers default off), so weather needs no
        // write; noon is world_clocks.dat written by save(), not a setDayTime call, since that method is gone at 26.x.
        PrimaryLevelData worldData = new PrimaryLevelData(
                settings, dimensions.specialWorldProperty(), dimensions.lifecycle());
        this.pendingWorldGenSettings = worldGenSettings;
        this.pendingGameRules = gameRules;
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

    /** The curated set keyed by the band's own rule id, the shape {@link WorldOutputConfig#resolveGameRules} merges. */
    private static Map<String, String> curatedByBandId() {
        Map<String, String> byBandId = new LinkedHashMap<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            byBandId.put(spec.bandId(), spec.curatedValue());
        }
        return byBandId;
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
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            GameRule<?> rule = byId.get(spec.bandId());
            if (rule == null) {
                continue; // a curated rule with no rule at this band is omitted, and the menu skips its order slot
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

    /** The rule's band default rendered to its raw string, for an integer rule's enabled (on) toggle position. */
    private static <T> String defaultRuleValue(GameRule<T> rule) {
        return rule.serialize(rule.defaultValue());
    }

    // At this band the WDL name and the band's rule id coincide; a band whose vanilla ids differ maps them here, and
    // drops a WDL rule with no band equivalent by omitting its spec.
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

        // The save-side files (worldgen, game rules, clocks) and the player file are all written BEFORE level.dat,
        // so any IO failure aborts before level.dat exists: an aborted save with no level.dat is invisible in
        // vanilla's world list, whereas a level.dat with a missing world_gen_settings.dat opens as a random-seed
        // world around the captured chunks, claiming success.
        UUID singleplayerUuid = null;
        if (player != null) {
            // buildLevelData always produces a PrimaryLevelData; the setters flip the fields createTag reads.
            PrimaryLevelData levelData = (PrimaryLevelData) worldData;
            levelData.setGameType(player.gameType());
            levelData.setSpawn(RespawnData.of(player.dimension(), player.spawnPos(), player.yaw(), player.pitch()));
            levelData.setDifficulty(player.difficulty());
            singleplayerUuid = writePlayerData(saveRoot, player.playerTag());
        }
        writeMetadata(saveRoot, registries, worldData);
        access.saveDataTag(worldData, singleplayerUuid);
    }

    /**
     * Write the player's captured entity tag to {@code players/data/<uuid>.dat}, the 26.x home for the local player
     * (level.dat carries no {@code "Player"} compound). Returns the uuid so the caller can stamp it as level.dat's
     * {@code singleplayer_uuid}; a client's {@code saveWithoutId} always carries {@code UUID}, so a missing one is a
     * corrupt capture the save must fail on rather than silently drop the whole player axis.
     */
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
     * Write the three namespaced save-side SavedData files under {@code data/minecraft/}: worldgen, game rules and the
     * world clocks. Any {@code IOException} aborts the save unchecked rather than catch-and-degrade: a swallowed
     * worldgen write regenerates a random-seed world, and a missing clocks file freezes the world at tick 0.
     */
    private void writeMetadata(Path saveRoot, RegistryAccess registries, WorldData worldData) {
        WorldGenSettings worldGenSettings = this.pendingWorldGenSettings;
        GameRules gameRules = this.pendingGameRules;
        if (worldGenSettings == null || gameRules == null) {
            throw new IllegalStateException("save() called before buildLevelData");
        }
        this.pendingWorldGenSettings = null;
        this.pendingGameRules = null;
        try {
            LevelStorageSource.writeWorldGenSettings(registries, saveRoot, worldGenSettings);
            LevelStorageSource.writeGameRules(worldData, saveRoot, gameRules);
            writeWorldClocks(saveRoot, registries);
        } catch (IOException e) {
            throw new UncheckedIOException("aborting the save: level metadata write failed", e);
        }
    }

    /**
     * Hand-write {@code data/minecraft/world_clocks.dat}: vanilla exports no writer (the {@code ServerClockManager}
     * constructor and its {@code writeSavedData} are both private). The WORLD_CLOCK registry is client-synced, so the
     * overworld holder comes straight from the composed registries with no worldgen reconstruction.
     */
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

    /**
     * The read mirror of {@link #writePlayerData}: level.dat carries no {@code "Player"} compound at 26.x, so the prior
     * player is found by reading level.dat's {@code singleplayer_uuid} and then {@code players/data/<uuid>.dat}. Null
     * when the folder is fresh, the level.dat stamps no uuid, or that player file is absent (fail-soft).
     */
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
