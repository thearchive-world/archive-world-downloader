// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;

/**
 * 1.11.2 chunk codec: replicates the client-safe slice of vanilla {@code AnvilChunkLoader}'s chunk write to NBT.
 * Vanilla's write reads from a {@code World} a multiplayer client never fully has, so {@link #encode} rebuilds the tag
 * field by field from the captured snapshot. Below the Flattening (this band's own cut) a section stores block state as
 * a numeric id plus a 4-bit metadata nibble rather than a palette, light is section-resident (each
 * {@link ExtendedBlockStorage} carries its own block and sky {@link NibbleArray}), and the root tag stamps
 * {@code DataVersion} 1343, not the post-Flattening 1631; there being no light engine yet, capture reads light straight
 * off the sections.
 *
 * <p>Two steps (see {@link ChunkCodec}): {@link #capture(Chunk)} captures a live client chunk into a
 * {@link ChunkSnapshotSource}, and {@link #encode(ChunkSnapshotSource, boolean)} encodes that snapshot to NBT (pure, so
 * the headless round-trip guards it).
 */
public final class ChunkCodecImpl implements ChunkCodec {
    // The 1.12.2 chunk NBT version, the Anvil DataVersion AnvilChunkLoader.saveChunk stamps at the root.
    private static final int CHUNK_DATA_VERSION = 1343;

    private static final int BLOCKS_BYTES = 4096;
    private static final int SECTION_LAYER_BYTES = 2048;

    // The 1.12.2 block-section column is 0..15. A client only ever holds sections in that range, so a section index
    // outside it cannot occur; the encode bound is a defensive guard on the pure surface.
    private static final int MIN_BLOCK_SECTION = 0;
    private static final int MAX_BLOCK_SECTION = 15;

    /**
     * Snapshot a live client chunk on the main thread (block-state section copies, per-chunk biomes, the heightmap,
     * live block-entity NBT), everything detached so only immutable data crosses to the async IO worker.
     *
     * <p>Light is read off each non-empty section's own layers, the way vanilla's save reads them: the block layer is
     * always present, and the sky layer only in a dimension that has sky light. A copy is taken so nothing live crosses
     * to the writer thread.
     */
    @Override
    public ChunkSnapshotSource capture(Chunk chunk) {
        World world = chunk.getWorld();
        ChunkPos pos = chunk.getPos();
        boolean hasSkyLight = world.provider.hasSkyLight();

        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        int minSectionY = 0;
        List<ChunkSnapshotSource.SectionData> sectionData = new ArrayList<>(sections.length);
        for (int sectionY = 0; sectionY < sections.length; sectionY++) {
            // A null slot is an all-air, unsaved section; vanilla saves only the non-null ones.
            ExtendedBlockStorage live = sections[sectionY];
            if (live == null) {
                continue;
            }
            NibbleArray blockLight = copyLayer(live.getBlockLight());
            NibbleArray skyLight = hasSkyLight ? copyLayer(live.getSkyLight()) : null;
            sectionData.add(new ChunkSnapshotSource.SectionData(sectionY,
                    copySection(sectionY, hasSkyLight, live), blockLight, skyLight));
        }

        List<NBTTagCompound> blockEntities = new ArrayList<>();
        for (TileEntity blockEntity : chunk.getTileEntityMap().values()) {
            NBTTagCompound tag = new NBTTagCompound();
            blockEntity.writeToNBT(tag);
            blockEntities.add(tag);
        }

        int[] biomes = columnBiomes(chunk);
        int[] heightMap = chunk.getHeightMap().clone();

        return new CapturedChunkSnapshot(pos, minSectionY, world.getTotalWorldTime(),
                chunk.getInhabitedTime(), chunk.isLightPopulated(), heightMap, sectionData, blockEntities, biomes);
    }

    @Override
    public NBTTagCompound encode(ChunkSnapshotSource snapshot, boolean synthesizeBlending) {
        ChunkPos pos = snapshot.chunkPos();
        NBTTagCompound levelTag = new NBTTagCompound();
        levelTag.setInteger("xPos", pos.x);
        levelTag.setInteger("zPos", pos.z);
        levelTag.setLong("LastUpdate", snapshot.gameTime());
        levelTag.setIntArray("HeightMap", snapshot.heightmaps());
        levelTag.setBoolean("TerrainPopulated", true);
        levelTag.setBoolean("LightPopulated", snapshot.lightCorrect());
        levelTag.setLong("InhabitedTime", snapshot.inhabitedTime());

        NBTTagList sectionsTag = new NBTTagList();
        for (ChunkSnapshotSource.SectionData section : snapshot.sections()) {
            ExtendedBlockStorage chunkSection = section.chunkSection();
            if (chunkSection == null || section.y() < MIN_BLOCK_SECTION || section.y() > MAX_BLOCK_SECTION) {
                continue;
            }
            NBTTagCompound sectionTag = new NBTTagCompound();
            sectionTag.setByte("Y", (byte) section.y());
            byte[] blocks = new byte[BLOCKS_BYTES];
            NibbleArray data = new NibbleArray();
            NibbleArray add = chunkSection.getData().getDataForNBT(blocks, data);
            sectionTag.setByteArray("Blocks", blocks);
            sectionTag.setByteArray("Data", data.getData());
            if (add != null) {
                sectionTag.setByteArray("Add", add.getData());
            }
            // Every non-empty section at this band carries both light arrays: the loader reads them straight into a
            // NibbleArray, which throws unless the byte array is exactly 2048 long, so an omitted or empty layer would
            // crash the chunk on load. Sky light is zero-filled in a dimension without sky light, matching vanilla.
            sectionTag.setByteArray("BlockLight", layerBytes(section.blockLight()));
            sectionTag.setByteArray("SkyLight", layerBytes(section.skyLight()));
            sectionsTag.appendTag(sectionTag);
        }
        levelTag.setTag("Sections", sectionsTag);
        levelTag.setByteArray("Biomes", narrowBiomes(snapshot.biomes()));

        NBTTagList blockEntitiesTag = new NBTTagList();
        for (NBTTagCompound blockEntity : snapshot.blockEntities()) {
            blockEntitiesTag.appendTag(blockEntity);
        }
        levelTag.setTag("TileEntities", blockEntitiesTag);
        // Client chunks carry no scheduled ticks (those are server state), so the tick list saves empty.
        levelTag.setTag("TileTicks", new NBTTagList());

        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Level", levelTag);
        tag.setInteger("DataVersion", CHUNK_DATA_VERSION);
        return tag;
    }

    /**
     * A detached copy of a live section. This band's {@link ExtendedBlockStorage} has no copy(), so the copy
     * reconstructs a fresh section (its constructor takes the section's bottom block y and whether the dimension has
     * sky light) and replays the block states into it. Biomes live per-chunk here, not on the section, and light is
     * carried separately, so only the block states are replayed.
     */
    private static ExtendedBlockStorage copySection(int sectionY, boolean hasSkyLight, ExtendedBlockStorage section) {
        ExtendedBlockStorage copy = new ExtendedBlockStorage(sectionY << 4, hasSkyLight);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.set(x, y, z, section.get(x, y, z));
                }
            }
        }
        return copy;
    }

    /**
     * The per-chunk biome ids for the 1.12.2 column: {@code chunk.getBiomeArray()} widened to {@code int}, matching
     * vanilla {@code AnvilChunkLoader}, which stores the same {@code byte[256]} verbatim under the {@code "Biomes"} key
     * a 1.12.2 client reads back. Widened (rather than narrowed) here so the shared {@link ChunkSnapshotSource} keeps
     * one {@code int[]} biome shape across bands; {@link #encode} narrows it back down for the on-disk key.
     */
    private static int[] columnBiomes(Chunk chunk) {
        byte[] biomes = chunk.getBiomeArray();
        int[] ids = new int[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            ids[i] = biomes[i] & 0xFF;
        }
        return ids;
    }

    /** {@code biomes}, narrowed back to the on-disk {@code byte[256]} (pre-Flattening biome ids fit a byte). */
    private static byte[] narrowBiomes(int[] biomes) {
        byte[] narrowed = new byte[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            narrowed[i] = (byte) biomes[i];
        }
        return narrowed;
    }

    /** A detached copy of a stored light layer. */
    private static NibbleArray copyLayer(NibbleArray layer) {
        return new NibbleArray(layer.getData().clone());
    }

    /** The layer's 2048-byte array, or a zero-filled one when the layer is absent (an unlit column). */
    private static byte[] layerBytes(@Nullable NibbleArray layer) {
        return layer != null ? layer.getData() : new byte[SECTION_LAYER_BYTES];
    }

    /** Immutable snapshot of a captured live chunk (only {@link NBTTagCompound}s and copies cross threads). */
    private static final class CapturedChunkSnapshot implements ChunkSnapshotSource {
        private final ChunkPos chunkPos;
        private final int minSectionY;
        private final long gameTime;
        private final long inhabitedTime;
        private final boolean lightCorrect;
        private final int[] heightMap;
        private final List<ChunkSnapshotSource.SectionData> sections;
        private final List<NBTTagCompound> blockEntities;
        private final int[] biomes;

        CapturedChunkSnapshot(ChunkPos chunkPos, int minSectionY, long gameTime, long inhabitedTime,
                boolean lightCorrect, int[] heightMap, List<ChunkSnapshotSource.SectionData> sections,
                List<NBTTagCompound> blockEntities, int[] biomes) {
            this.chunkPos = chunkPos;
            this.minSectionY = minSectionY;
            this.gameTime = gameTime;
            this.inhabitedTime = inhabitedTime;
            this.lightCorrect = lightCorrect;
            this.heightMap = heightMap;
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
            return heightMap;
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
