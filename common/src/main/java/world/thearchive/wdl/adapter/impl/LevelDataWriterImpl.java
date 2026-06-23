// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
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
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.LevelDataWriter;

/**
 * 1.21.11 {@code level.dat} writer for the superflat VOID world (all air, built from the client's synced {@code BIOME}
 * + {@code DIMENSION_TYPE} registries). The captured chunks always supply the real terrain; the generator only fills
 * the un-captured gaps.
 */
public final class LevelDataWriterImpl implements LevelDataWriter {
    private static final String LEVEL_NAME = "Archive World Downloader";

    /** Server seed is unknown (not transmitted); irrelevant for void gen (no terrain is generated). */
    private static final long PLACEHOLDER_SEED = 0L;

    /** 24000-tick day; 6000 is noon. */
    private static final long NOON = 6000L;

    // SpecialWorldProperty is vanilla-deprecated, but the only public PrimaryLevelData ctor still
    // requires it; we take it from the baked dimensions (FLAT, for this superflat void world).
    @SuppressWarnings("deprecation")
    @Override
    public LevelData buildLevelData(RegistryAccess clientRegistries, @Nullable String worldName) {
        // The dimensions and the encode context must share one registry set (their generator holders must be owned by
        // the registries the encode resolves against), so both derive here.
        WorldDimensions.Complete dimensions = voidDimensions(clientRegistries);
        RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(
                Stream.concat(clientRegistries.registries(), dimensions.dimensionsRegistryAccess().registries()))
                        .freeze();

        GameRules gameRules = new GameRules(FeatureFlags.DEFAULT_FLAGS);

        String levelName = worldName == null || worldName.isEmpty() ? LEVEL_NAME : worldName;
        LevelSettings settings = new LevelSettings(
                levelName, GameType.SURVIVAL, false, Difficulty.NORMAL, false,
                gameRules, WorldDataConfiguration.DEFAULT);
        WorldOptions worldOptions = new WorldOptions(PLACEHOLDER_SEED, false, false); // no structures for void

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
        return new LevelData(worldData, registries);
    }

    @Override
    public void save(LevelStorageSource.LevelStorageAccess access, LevelData data) {
        // 1.21.x form: saveDataTag(RegistryAccess, WorldData). The 26.x band's implementation uses the
        // single-argument saveDataTag(WorldData) form (the RegistryAccess argument was dropped at 26.1.2).
        access.saveDataTag(data.registries(), data.worldData());
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
