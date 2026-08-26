// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import world.thearchive.wdl.adapter.ChunkSnapshotSource;

/**
 * Synthetic {@link ChunkSnapshotSource} fixtures for the headless codec round-trip.
 *
 * <p>These build {@link ExtendedBlockStorage}s (known blocks, PLAINS biomes) with no {@code World}, which a headless
 * {@code Chunk} cannot avoid, so the round-trip exercises the mod's encode slice.
 */
public final class SyntheticChunks {
    /** A standard 1.12.2 overworld column: 0..256, so {@code minSectionY == 0}. */
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 256;
    public static final long GAME_TIME = 1234L;

    /** A recognizable HeightMap value so the round-trip can assert the kept heightmap survives. */
    public static final int WORLD_SURFACE_SENTINEL = 63;

    /** Uniform per-cell light levels for the lit fixture, distinct so a swapped layer is caught. */
    public static final byte BLOCK_LIGHT_FILL = 7;
    public static final byte SKY_LIGHT_FILL = 15;

    private SyntheticChunks() {}

    /**
     * A chunk at (0,0): a single stone block in the bottom section, the rest air, PLAINS biomes. {@code lightCorrect}
     * is caller-controlled and maps onto this band's on-disk {@code LightPopulated} boolean.
     */
    public static ChunkSnapshotSource full(boolean lightCorrect) {
        return fullWithBlockEntities(lightCorrect, ImmutableList.of());
    }

    /**
     * As {@link #full} but with the given block-entity NBT (saved form, each carrying its own {@code id} +
     * {@code x/y/z}). Models a chunk re-captured after a block entity is placed, edited, or broken, so the codec
     * round-trip can prove a re-encode reflects the current block-entity set.
     */
    public static ChunkSnapshotSource fullWithBlockEntities(boolean lightCorrect,
            List<NBTTagCompound> blockEntities) {
        return fullWithBlockEntities(lightCorrect, blockEntities, true);
    }

    /**
     * As {@link #fullWithBlockEntities} without the producer-shape check, for a case whose subject IS a shape no
     * producer emits: a tag from a foreign or modded server that the codec must pass through opaquely.
     */
    public static ChunkSnapshotSource fullWithMalformedBlockEntities(boolean lightCorrect,
            List<NBTTagCompound> blockEntities) {
        return fullWithBlockEntities(lightCorrect, blockEntities, false);
    }

    private static ChunkSnapshotSource fullWithBlockEntities(boolean lightCorrect,
            List<NBTTagCompound> blockEntities, boolean checkShape) {
        TestRegistries.bootstrap();
        int minSectionY = MIN_Y;

        ExtendedBlockStorage bottom = new ExtendedBlockStorage(minSectionY << 4, true);
        bottom.set(0, 0, 0, Blocks.STONE.getDefaultState());
        ExtendedBlockStorage air = new ExtendedBlockStorage((minSectionY + 1) << 4, true);

        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY, bottom, null, null));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY + 1, air, null, null));

        return new Snapshot(
                new ChunkPos(0, 0), minSectionY, GAME_TIME, 0L,
                lightCorrect, standardHeightmap(), sections, saved(blockEntities, checkShape),
                plainsBiomes());
    }

    /**
     * Every block entity as the chunk layer hands it over, which is where a snapshot's list comes from in production
     * ({@code Chunk.getTileEntityMap}): checked against its producer's shape, then stamped with the {@code keepPacked}
     * only that layer writes. This is the choke point for the snapshot axis, the way {@code chunkTagWith} is for a
     * serialized chunk tag; without it a snapshot is a third way a hand-built block entity reaches production code
     * unchecked.
     */
    private static List<NBTTagCompound> saved(List<NBTTagCompound> blockEntities, boolean checkShape) {
        List<NBTTagCompound> stamped = new ArrayList<>();
        for (NBTTagCompound blockEntity : blockEntities) {
            NBTTagCompound copy = blockEntity.copy();
            if (checkShape) {
                FixtureFidelity.assertBlockEntityShape(copy);
            }
            copy.setBoolean(FixtureFidelity.KEEP_PACKED, false);
            stamped.add(copy);
        }
        return ImmutableList.copyOf(stamped);
    }

    /** The flat {@code int[256]} heightmap this band carries, one entry marked with the sentinel. */
    private static int[] standardHeightmap() {
        int[] heightMap = new int[HEIGHT];
        heightMap[0] = WORLD_SURFACE_SENTINEL;
        return heightMap;
    }

    /**
     * A snapshot of {@code worldPos}'s chunk carrying {@code state} at {@code worldPos} (the rest air), for the
     * reconcile gate's block-state read: only the one section containing {@code worldPos} is built, its {@code y()} set
     * so the section lookup ({@code pos.getY() >> 4}) resolves it.
     */
    public static ChunkSnapshotSource withBlockAt(BlockPos worldPos, IBlockState state) {
        TestRegistries.bootstrap();
        ExtendedBlockStorage section = new ExtendedBlockStorage((worldPos.getY() >> 4) << 4, true);
        section.set(worldPos.getX() & 15, worldPos.getY() & 15, worldPos.getZ() & 15, state);
        List<ChunkSnapshotSource.SectionData> sections = ImmutableList
                .of(new ChunkSnapshotSource.SectionData(worldPos.getY() >> 4, section, null, null));
        return new Snapshot(
                new ChunkPos(worldPos), MIN_Y, GAME_TIME, 0L,
                true, new int[HEIGHT], sections, ImmutableList.of(), plainsBiomes());
    }

    /**
     * As {@link #withBlockAt} but also carrying {@code blockEntity} in the snapshot's block-entity list, for a path
     * that has to find a block entity and then read the block-state under it.
     */
    public static ChunkSnapshotSource withBlockEntityAt(BlockPos worldPos,
            IBlockState state, NBTTagCompound blockEntity) {
        return withBlockEntityAt(worldPos, state, blockEntity, true);
    }

    /**
     * As {@link #withBlockEntityAt} without the producer-shape check, for a case whose subject is a tag a producer
     * never writes, such as one with a coordinate deliberately removed to prove the reader drops it.
     */
    public static ChunkSnapshotSource withMalformedBlockEntityAt(BlockPos worldPos,
            IBlockState state, NBTTagCompound blockEntity) {
        return withBlockEntityAt(worldPos, state, blockEntity, false);
    }

    private static ChunkSnapshotSource withBlockEntityAt(BlockPos worldPos,
            IBlockState state, NBTTagCompound blockEntity, boolean checkShape) {
        TestRegistries.bootstrap();
        ExtendedBlockStorage section = new ExtendedBlockStorage((worldPos.getY() >> 4) << 4, true);
        section.set(worldPos.getX() & 15, worldPos.getY() & 15, worldPos.getZ() & 15, state);
        List<ChunkSnapshotSource.SectionData> sections = ImmutableList
                .of(new ChunkSnapshotSource.SectionData(worldPos.getY() >> 4, section, null, null));
        return new Snapshot(
                new ChunkPos(worldPos), MIN_Y, GAME_TIME, 0L,
                true, new int[HEIGHT], sections, saved(ImmutableList.of(blockEntity), checkShape),
                plainsBiomes());
    }

    /**
     * As {@link #full} with {@code lightCorrect=true} and captured light layers, including a below-chunk padding
     * section (sky only, null chunk section). Proves the encode slice writes the vanilla section-resident
     * {@code BlockLight}/{@code SkyLight} shape for the in-range sections and drops a section outside the 0..15 block
     * column, which has no home in this band's on-disk shape.
     */
    public static ChunkSnapshotSource fullWithLight() {
        TestRegistries.bootstrap();
        int minSectionY = MIN_Y;

        ExtendedBlockStorage bottom = new ExtendedBlockStorage(minSectionY << 4, true);
        bottom.set(0, 0, 0, Blocks.STONE.getDefaultState());
        ExtendedBlockStorage air = new ExtendedBlockStorage((minSectionY + 1) << 4, true);

        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY - 1, null, null,
                new NibbleArray(lightFill(SKY_LIGHT_FILL))));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY, bottom,
                new NibbleArray(lightFill(BLOCK_LIGHT_FILL)), new NibbleArray(lightFill(SKY_LIGHT_FILL))));
        sections.add(new ChunkSnapshotSource.SectionData(minSectionY + 1, air, null,
                new NibbleArray(lightFill(SKY_LIGHT_FILL))));

        return new Snapshot(new ChunkPos(0, 0), minSectionY, GAME_TIME, 0L,
                true, standardHeightmap(), ImmutableList.copyOf(sections), ImmutableList.of(),
                plainsBiomes());
    }

    /** A 2048-byte nibble array with every cell at {@code level} (both nibbles of each byte). */
    public static byte[] lightFill(byte level) {
        byte[] data = new byte[2048];
        Arrays.fill(data, (byte) ((level & 15) | ((level & 15) << 4)));
        return data;
    }

    /** PLAINS-filled per-column biome ids: the flat 16x16 grid this band carries, 256 columns indexed z*16+x. */
    private static int[] plainsBiomes() {
        int plainsId = Biome.getIdForBiome(Biomes.PLAINS);
        int[] biomes = new int[16 * 16];
        Arrays.fill(biomes, plainsId);
        return biomes;
    }

    private static final class Snapshot implements ChunkSnapshotSource {
        private final ChunkPos chunkPos;
        private final int minSectionY;
        private final long gameTime;
        private final long inhabitedTime;
        private final boolean lightCorrect;
        private final int[] heightmap;
        private final List<ChunkSnapshotSource.SectionData> sections;
        private final List<NBTTagCompound> blockEntities;
        private final int[] biomes;

        Snapshot(ChunkPos chunkPos, int minSectionY, long gameTime, long inhabitedTime,
                boolean lightCorrect, int[] heightmap,
                List<ChunkSnapshotSource.SectionData> sections, List<NBTTagCompound> blockEntities, int[] biomes) {
            this.chunkPos = chunkPos;
            this.minSectionY = minSectionY;
            this.gameTime = gameTime;
            this.inhabitedTime = inhabitedTime;
            this.lightCorrect = lightCorrect;
            this.heightmap = heightmap;
            this.sections = sections;
            this.blockEntities = blockEntities;
            this.biomes = biomes;
        }

        @Override
        public ChunkPos chunkPos() {
            return chunkPos;
        }

        @Override
        public int minSectionY() {
            return minSectionY;
        }

        @Override
        public long gameTime() {
            return gameTime;
        }

        @Override
        public long inhabitedTime() {
            return inhabitedTime;
        }

        @Override
        public boolean lightCorrect() {
            return lightCorrect;
        }

        @Override
        public int[] heightmaps() {
            return heightmap;
        }

        @Override
        public List<ChunkSnapshotSource.SectionData> sections() {
            return sections;
        }

        @Override
        public List<NBTTagCompound> blockEntities() {
            return blockEntities;
        }

        @Override
        public int[] biomes() {
            return biomes;
        }
    }
}
