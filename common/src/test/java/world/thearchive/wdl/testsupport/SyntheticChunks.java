// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import world.thearchive.wdl.adapter.ChunkSnapshotSource;

public final class SyntheticChunks {
    public static final int MIN_Y = -64;
    public static final int HEIGHT = 384;
    public static final long GAME_TIME = 1234L;

    public static final long WORLD_SURFACE_SENTINEL = 0x0102030405060708L;

    /** Uniform per-cell light levels for the lit fixture, distinct so a swapped layer is caught. */
    public static final byte BLOCK_LIGHT_FILL = 7;
    public static final byte SKY_LIGHT_FILL = 15;

    /** Packed long[] length of a 384-tall world's heightmap. */
    private static final int HEIGHTMAP_LONGS = 37;

    private SyntheticChunks() {}

    /** The height accessor matching {@link #full}. */
    public static LevelHeightAccessor heightAccessor() {
        return LevelHeightAccessor.create(MIN_Y, HEIGHT);
    }

    /**
     * A FULL chunk at (0,0): a single stone block in the bottom section, the rest air, PLAINS biomes. Its heightmap set
     * deliberately includes {@code OCEAN_FLOOR} ({@code Usage.LIVE_WORLD}, never sent to a client), so the codec is
     * proven to drop it. {@code lightCorrect} is caller-controlled so a test can prove {@code isLightOn} tracks the
     * flag rather than being hardcoded.
     */
    public static ChunkSnapshotSource full(RegistryAccess registries, boolean lightCorrect) {
        return fullWithBlockEntities(registries, lightCorrect, List.of());
    }

    /**
     * As {@link #full} but with the given block-entity NBT (saved form, each carrying its own {@code id} +
     * {@code x/y/z}).
     */
    public static ChunkSnapshotSource fullWithBlockEntities(RegistryAccess registries, boolean lightCorrect,
            List<CompoundTag> blockEntities) {
        return fullWithBlockEntities(registries, lightCorrect, blockEntities, true);
    }

    /**
     * As {@link #fullWithBlockEntities} without the producer-shape check.
     */
    public static ChunkSnapshotSource fullWithMalformedBlockEntities(RegistryAccess registries,
            boolean lightCorrect, List<CompoundTag> blockEntities) {
        return fullWithBlockEntities(registries, lightCorrect, blockEntities, false);
    }

    private static ChunkSnapshotSource fullWithBlockEntities(RegistryAccess registries, boolean lightCorrect,
            List<CompoundTag> blockEntities, boolean checkShape) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        int minSectionY = heightAccessor().getMinSection();

        LevelChunkSection bottom = new LevelChunkSection(minSectionY, biomeRegistry);
        bottom.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        LevelChunkSection air = new LevelChunkSection(minSectionY + 1, biomeRegistry);

        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY, bottom, null, null));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY + 1, air, null, null));

        return new Snapshot(
                new ChunkPos(0, 0), minSectionY, GAME_TIME, 0L, ChunkStatus.FULL,
                lightCorrect, standardHeightmaps(), sections, saved(blockEntities, checkShape));
    }

    /**
     * Every block entity, checked against its producer's shape when {@code checkShape} is set; without the check a
     * snapshot is a way a hand-built block entity reaches production code unchecked.
     */
    private static List<CompoundTag> saved(List<CompoundTag> blockEntities, boolean checkShape) {
        List<CompoundTag> stamped = new ArrayList<>();
        for (CompoundTag blockEntity : blockEntities) {
            CompoundTag copy = blockEntity.copy();
            if (checkShape) {
                FixtureFidelity.assertBlockEntityShape(copy);
            }
            copy.putBoolean(FixtureFidelity.KEEP_PACKED, false);
            stamped.add(copy);
        }
        return List.copyOf(stamped);
    }

    private static Map<Heightmap.Types, long[]> standardHeightmaps() {
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        heightmaps.put(Heightmap.Types.OCEAN_FLOOR, new long[HEIGHTMAP_LONGS]);
        heightmaps.put(Heightmap.Types.WORLD_SURFACE, filled(WORLD_SURFACE_SENTINEL));
        heightmaps.put(Heightmap.Types.MOTION_BLOCKING, new long[HEIGHTMAP_LONGS]);
        heightmaps.put(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new long[HEIGHTMAP_LONGS]);
        return heightmaps;
    }

    /**
     * A snapshot of {@code worldPos}'s chunk carrying {@code state} at {@code worldPos}.
     */
    public static ChunkSnapshotSource withBlockAt(RegistryAccess registries, BlockPos worldPos, BlockState state) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(worldPos.getY() >> 4, biomeRegistry);
        section.setBlockState(worldPos.getX() & 15, worldPos.getY() & 15, worldPos.getZ() & 15, state);
        List<ChunkSnapshotSource.SectionData> sections = List
                .of(new ChunkSnapshotSource.SectionData(worldPos.getY() >> 4, section, null, null));
        return new Snapshot(
                new ChunkPos(worldPos), heightAccessor().getMinSection(), GAME_TIME, 0L, ChunkStatus.FULL,
                true, new EnumMap<>(Heightmap.Types.class), sections, List.of());
    }

    /**
     * As {@link #withBlockAt} but also carrying {@code blockEntity} in the snapshot's block-entity list.
     */
    public static ChunkSnapshotSource withBlockEntityAt(RegistryAccess registries, BlockPos worldPos,
            BlockState state, CompoundTag blockEntity) {
        return withBlockEntityAt(registries, worldPos, state, blockEntity, true);
    }

    /**
     * As {@link #withBlockEntityAt} without the producer-shape check.
     */
    public static ChunkSnapshotSource withMalformedBlockEntityAt(RegistryAccess registries, BlockPos worldPos,
            BlockState state, CompoundTag blockEntity) {
        return withBlockEntityAt(registries, worldPos, state, blockEntity, false);
    }

    private static ChunkSnapshotSource withBlockEntityAt(RegistryAccess registries, BlockPos worldPos,
            BlockState state, CompoundTag blockEntity, boolean checkShape) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(worldPos.getY() >> 4, biomeRegistry);
        section.setBlockState(worldPos.getX() & 15, worldPos.getY() & 15, worldPos.getZ() & 15, state);
        List<ChunkSnapshotSource.SectionData> sections = List
                .of(new ChunkSnapshotSource.SectionData(worldPos.getY() >> 4, section, null, null));
        return new Snapshot(
                new ChunkPos(worldPos), heightAccessor().getMinSection(), GAME_TIME, 0L, ChunkStatus.FULL,
                true, new EnumMap<>(Heightmap.Types.class), sections, saved(List.of(blockEntity), checkShape));
    }

    /**
     * As {@link #full} with {@code lightCorrect=true} and captured light layers, including a below-chunk padding
     * section (sky only, null chunk section), the shape the light engine's padded range produces. Proves the encode
     * slice writes the vanilla {@code BlockLight}/{@code SkyLight}/{@code isLightOn} shape and that a null-section
     * entry survives write and parse.
     */
    public static ChunkSnapshotSource fullWithLight(RegistryAccess registries) {
        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        int minSectionY = heightAccessor().getMinSection();

        LevelChunkSection bottom = new LevelChunkSection(minSectionY, biomeRegistry);
        bottom.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        LevelChunkSection air = new LevelChunkSection(minSectionY + 1, biomeRegistry);

        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY - 1, null, null,
                new DataLayer(lightFill(SKY_LIGHT_FILL))));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY, bottom,
                new DataLayer(lightFill(BLOCK_LIGHT_FILL)), new DataLayer(lightFill(SKY_LIGHT_FILL))));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY + 1, air, null,
                new DataLayer(lightFill(SKY_LIGHT_FILL))));

        return new Snapshot(new ChunkPos(0, 0), minSectionY, GAME_TIME, 0L, ChunkStatus.FULL,
                true, standardHeightmaps(), List.copyOf(sections), List.of());
    }

    /** A 2048-byte nibble array with every cell at {@code level} (both nibbles of each byte). */
    public static byte[] lightFill(byte level) {
        byte[] data = new byte[2048];
        Arrays.fill(data, (byte) ((level & 15) | ((level & 15) << 4)));
        return data;
    }

    private static long[] filled(long value) {
        long[] data = new long[HEIGHTMAP_LONGS];
        Arrays.fill(data, value);
        return data;
    }

    private record Snapshot(
            ChunkPos chunkPos,
            int minSectionY,
            long gameTime,
            long inhabitedTime,
            ChunkStatus status,
            boolean lightCorrect,
            Map<Heightmap.Types, long[]> heightmaps,
            List<ChunkSnapshotSource.SectionData> sections,
            List<CompoundTag> blockEntities) implements ChunkSnapshotSource {}
}
