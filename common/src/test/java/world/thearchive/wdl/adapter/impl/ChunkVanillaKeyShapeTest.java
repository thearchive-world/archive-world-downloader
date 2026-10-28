// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;
import world.thearchive.wdl.testsupport.TestRegistries;

class ChunkVanillaKeyShapeTest {
    private static final int NON_EMPTY_SECTION_Y = 0;

    @BeforeAll
    static void bootstrapVanilla() {
        // This fixture reads Blocks, which throws "Accessed Blocks before Bootstrap!" from its own class initializer if
        // nothing has registered yet, and a failed initializer stays failed for the life of the JVM.
        TestRegistries.bootstrap();
    }

    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void encodesTheVanillaPreFlatteningShape() {
        NBTTagCompound root = codec.encode(nonEmptyChunk(), false);

        assertEquals(512, root.getInteger("DataVersion"), "root DataVersion must be the 1.10.2 Anvil version");
        assertNotEquals(922, root.getInteger("DataVersion"), "root DataVersion must not be the parent band's 922");

        NBTTagCompound level = root.getCompoundTag("Level");
        assertFalse(level.hasKey("V"), "the legacy McRegion V byte must not be written");
        assertFalse(level.hasKey("Heightmaps"), "the post-Flattening keyed Heightmaps compound must not be written");
        assertFalse(level.hasKey("Status"), "the post-Flattening chunk Status must not be written");

        assertTrue(level.getBoolean("TerrainPopulated"), "a captured client chunk is always terrain-populated");
        assertTrue(level.hasKey("LightPopulated"), "LightPopulated must be written");

        int[] heightMap = level.getIntArray("HeightMap");
        assertEquals(256, heightMap.length, "HeightMap must be the flat int[256], not a keyed heightmap compound");

        byte[] biomes = level.getByteArray("Biomes");
        assertEquals(256, biomes.length, "Biomes must be a byte[256], not an int[] biome-id array");

        NBTTagList sections = level.getTagList("Sections", 10);
        assertTrue(sections.tagCount() > 0, "the synthetic non-empty section must be written");
        NBTTagCompound section = sections.getCompoundTagAt(0);

        assertEquals((byte) NON_EMPTY_SECTION_Y, section.getByte("Y"));
        assertFalse(section.hasKey("Palette"), "the post-Flattening block-state Palette must not be written");
        assertFalse(section.hasKey("BlockStates"),
                "the post-Flattening packed BlockStates long array must not be written");

        byte[] blocks = section.getByteArray("Blocks");
        assertEquals(4096, blocks.length, "Blocks must be the flat numeric byte[4096]");
        assertTrue(section.hasKey("Data"), "the Data nibble array must be written alongside Blocks");
    }

    private static ChunkSnapshotSource nonEmptyChunk() {
        ExtendedBlockStorage section = new ExtendedBlockStorage(NON_EMPTY_SECTION_Y << 4, true);
        section.set(0, 0, 0, Blocks.STONE.getDefaultState());

        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(NON_EMPTY_SECTION_Y, section, new NibbleArray(),
                new NibbleArray()));

        return new SyntheticSnapshot(sections);
    }

    private static final class SyntheticSnapshot implements ChunkSnapshotSource {
        private final List<SectionData> sections;

        SyntheticSnapshot(List<SectionData> sections) {
            this.sections = sections;
        }

        @Override
        public ChunkPos chunkPos() {
            return new ChunkPos(0, 0);
        }

        @Override
        public int minSectionY() {
            return 0;
        }

        @Override
        public long gameTime() {
            return 100L;
        }

        @Override
        public long inhabitedTime() {
            return 0L;
        }

        @Override
        public boolean lightCorrect() {
            return true;
        }

        @Override
        public int[] heightmaps() {
            return new int[256];
        }

        @Override
        public List<SectionData> sections() {
            return sections;
        }

        @Override
        public List<NBTTagCompound> blockEntities() {
            return Collections.emptyList();
        }

        @Override
        public int[] biomes() {
            return new int[256];
        }
    }
}
