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
 * 1.21.5 {@code level.dat} writer for the selected generator: the default superflat VOID (all air, built from the
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
     * The curated safe set: each rule's stable WDL name, the running band's own id for it, and the curated raw value.
     * The WDL name is what the menu and the lang catalogs bind (band-stable); the band id is what the download writes.
     * Every curated rule is a boolean here: the newer bands' integer fire rule has no 1.21.4 id and carries no spec.
     * The user's gamerule.* overrides, keyed by band id, are validated and applied on top of this (see
     * {@link WorldOutputConfig}).
     */
    private static final List<CuratedSpec> CURATED_GAME_RULES = buildCuratedGameRules();

    /** One curated rule: its stable WDL name, the running band's id for it, and the curated raw value. */
    private record CuratedSpec(String wdlId, String bandId, String curatedValue) {}

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

    /** The curated set keyed by the band's own rule id, the shape {@link WorldOutputConfig#resolveGameRules} merges. */
    private static Map<String, String> curatedByBandId() {
        Map<String, String> byBandId = new LinkedHashMap<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            byBandId.put(spec.bandId(), spec.curatedValue());
        }
        return byBandId;
    }

    /** Whether the raw string is a valid value for this rule: true or false for a boolean, an int for an integer. */
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

    /** Set one validated rule on its offline GameRules value (no server, so the change-callback is skipped). */
    private static void setRule(GameRules.Value<?> rule, String rawValue) {
        if (rule instanceof GameRules.BooleanValue booleanValue) {
            booleanValue.set(Boolean.parseBoolean(rawValue), null);
        } else if (rule instanceof GameRules.IntegerValue integerValue) {
            integerValue.tryDeserialize(rawValue);
        }
    }

    /** Index the rules available at this band (feature-filtered) by their short id, for lookup and validation. */
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
        Map<String, GameRules.Value<?>> byId = availableRulesById(new GameRules(FeatureFlags.DEFAULT_FLAGS));
        List<CuratedGameRule> rules = new ArrayList<>();
        for (CuratedSpec spec : CURATED_GAME_RULES) {
            GameRules.Value<?> rule = byId.get(spec.bandId());
            if (rule == null) {
                continue; // a curated rule with no rule at this band is omitted, and the menu skips its order slot
            }
            if (rule instanceof GameRules.BooleanValue) {
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), "true", "false"));
            } else {
                // An integer rule's enabled position is its band default (serialized), and disabled is 0.
                rules.add(new CuratedGameRule(spec.wdlId(), spec.bandId(), spec.curatedValue(), rule.serialize(), "0"));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    // The WDL names are dev's band-neutral keys; each maps to its 1.21.4 vanilla rule id here, and the newer bands'
    // fire rule is dropped by omitting its spec, since 1.21.4 has no equivalent.
    private static List<CuratedSpec> buildCuratedGameRules() {
        List<CuratedSpec> curated = new ArrayList<>();
        curated.add(new CuratedSpec("spawn_mobs", "doMobSpawning", "false"));
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
        // Do not port to 26.x by picking the nearest saveDataTag overload: no overload there carries a player
        // compound, so that band must write players/data/<uuid>.dat itself.
        if (player == null) {
            access.saveDataTag(data.registries(), data.worldData());
            return;
        }
        // buildLevelData always produces a PrimaryLevelData; the setters flip the fields createTag reads.
        PrimaryLevelData levelData = (PrimaryLevelData) data.worldData();
        levelData.setGameType(player.gameType());
        levelData.setSpawn(player.spawnPos(), player.yaw());
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
            CompoundTag root = NbtIo.readCompressed(levelDatFile, NbtAccounter.unlimitedHeap());
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
