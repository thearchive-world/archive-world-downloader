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

public final class ChunkCodecImpl implements ChunkCodec {
    // The 1.12.2 chunk NBT version, the Anvil DataVersion AnvilChunkLoader.saveChunk stamps at the root.
    private static final int CHUNK_DATA_VERSION = 1343;

    private static final int BLOCKS_BYTES = 4096;
    private static final int SECTION_LAYER_BYTES = 2048;

    private static final int MIN_BLOCK_SECTION = 0;
    private static final int MAX_BLOCK_SECTION = 15;

    /**
     * Snapshot a live client chunk on the main thread (block-state section copies, per-chunk biomes, the heightmap),
     * detached so none of them crosses live to the async IO worker.
     *
     * <p>Light is read off each section's own layers: the block layer is always present, and the sky layer only in a
     * dimension that has sky light. A copy is taken so nothing live crosses to the writer thread.
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
            // A null slot is an all-air, unsaved section.
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
            // Every non-empty section at this band carries both light arrays: the loader reads the block layer, and the
            // sky layer in a dimension with sky light, straight into a NibbleArray, which throws unless the byte array
            // is exactly 2048 long, so an omitted or empty layer would crash the chunk on load.
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
        levelTag.setTag("TileTicks", new NBTTagList());

        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Level", levelTag);
        tag.setInteger("DataVersion", CHUNK_DATA_VERSION);
        return tag;
    }

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

    private static int[] columnBiomes(Chunk chunk) {
        byte[] biomes = chunk.getBiomeArray();
        int[] ids = new int[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            ids[i] = biomes[i] & 0xFF;
        }
        return ids;
    }

    private static byte[] narrowBiomes(int[] biomes) {
        byte[] narrowed = new byte[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            narrowed[i] = (byte) biomes[i];
        }
        return narrowed;
    }

    private static NibbleArray copyLayer(NibbleArray layer) {
        return new NibbleArray(layer.getData().clone());
    }

    private static byte[] layerBytes(@Nullable NibbleArray layer) {
        return layer != null ? layer.getData() : new byte[SECTION_LAYER_BYTES];
    }

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
