// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

class ChunkSectionClampTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void sectionsOutsideTheBlockColumnAreDroppedSoTheSaveLoads() {
        TestRegistries.bootstrap();
        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(-4, blockSection(-4), null, null));
        sections.add(new ChunkSnapshotSource.SectionData(-1, null, null, filledLight()));
        sections.add(new ChunkSnapshotSource.SectionData(0, blockSection(0), null, null));
        sections.add(new ChunkSnapshotSource.SectionData(15, blockSection(15), null, null));
        sections.add(new ChunkSnapshotSource.SectionData(16, null, null, filledLight()));
        sections.add(new ChunkSnapshotSource.SectionData(17, blockSection(17), null, null));

        NBTTagCompound level = codec.encode(new ClampSnapshot(sections), false).getCompoundTag("Level");

        Set<Integer> writtenY = new HashSet<>();
        Set<Integer> withBlockData = new HashSet<>();
        int count = level.getTagList("Sections", 10).tagCount();
        for (int i = 0; i < count; i++) {
            NBTTagCompound section = level.getTagList("Sections", 10).getCompoundTagAt(i);
            int y = section.getByte("Y");
            writtenY.add(y);
            if (section.hasKey("Blocks")) {
                withBlockData.add(y);
            }
        }
        assertEquals(ImmutableSet.of(0, 15), writtenY,
                "only the in-range block sections survive; the out-of-range and section-less light pads are dropped");
        assertEquals(ImmutableSet.of(0, 15), withBlockData, "block data is written only inside the 0..15 block range");
    }

    private static ExtendedBlockStorage blockSection(int sectionY) {
        ExtendedBlockStorage section = new ExtendedBlockStorage(sectionY, true);
        section.set(0, 0, 0, Blocks.STONE.getDefaultState());
        return section;
    }

    private static NibbleArray filledLight() {
        return new NibbleArray(SyntheticChunks.lightFill((byte) 15));
    }

    private static final class ClampSnapshot implements ChunkSnapshotSource {
        private final List<ChunkSnapshotSource.SectionData> sections;

        ClampSnapshot(List<ChunkSnapshotSource.SectionData> sections) {
            this.sections = sections;
        }

        @Override
        public List<ChunkSnapshotSource.SectionData> sections() {
            return sections;
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
            return 0L;
        }

        @Override
        public long inhabitedTime() {
            return 0L;
        }

        @Override
        public boolean lightCorrect() {
            return false;
        }

        @Override
        public int[] heightmaps() {
            return new int[256];
        }

        @Override
        public List<NBTTagCompound> blockEntities() {
            return ImmutableList.of();
        }

        @Override
        public int[] biomes() {
            return new int[16 * 16];
        }
    }
}
