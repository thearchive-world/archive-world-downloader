// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * A downgrade proxy (ViaBackwards) can hand a 1.13.2 client sections outside the 0..255 column. A block section outside
 * 0..15 crashes vanilla's unchecked {@code sections[index]} on load, and this band has no light-pad column (light is
 * section-resident, not engine-driven, so a null-section light-only entry is a 1.14 shape that has no home here). The
 * codec therefore writes only the block sections inside 0..15 and drops every out-of-range or section-less entry.
 */
class ChunkSectionClampTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void sectionsOutsideTheBlockColumnAreDroppedSoTheSaveLoads() {
        TestRegistries.bootstrap();
        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(-4, blockSection(-4), null, null)); // out of range, dropped
        sections.add(new ChunkSnapshotSource.SectionData(-1, null, null, filledLight())); // section-less pad, dropped
        sections.add(new ChunkSnapshotSource.SectionData(0, blockSection(0), null, null)); // in range, kept
        sections.add(new ChunkSnapshotSource.SectionData(15, blockSection(15), null, null)); // in range, kept
        sections.add(new ChunkSnapshotSource.SectionData(16, null, null, filledLight())); // section-less pad, dropped
        sections.add(new ChunkSnapshotSource.SectionData(17, blockSection(17), null, null)); // out of range, dropped

        CompoundTag level = codec.encode(new ClampSnapshot(sections), false).getCompound("Level");

        Set<Integer> writtenY = new HashSet<>();
        Set<Integer> withBlockData = new HashSet<>();
        for (Tag sectionTag : level.getList("Sections", 10)) {
            CompoundTag section = (CompoundTag) sectionTag;
            int y = section.getByte("Y");
            writtenY.add(y);
            if (section.contains("BlockStates")) {
                withBlockData.add(y);
            }
        }
        assertEquals(ImmutableSet.of(0, 15), writtenY,
                "only the in-range block sections survive; the out-of-range and section-less light pads are dropped");
        assertEquals(ImmutableSet.of(0, 15), withBlockData, "block data is written only inside the 0..15 block range");
    }

    private static LevelChunkSection blockSection(int sectionY) {
        LevelChunkSection section = new LevelChunkSection(sectionY, true);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        return section;
    }

    private static DataLayer filledLight() {
        return new DataLayer(SyntheticChunks.lightFill((byte) 15));
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
        public ChunkStatus status() {
            return ChunkStatus.field_18865;
        }

        @Override
        public boolean lightCorrect() {
            return false;
        }

        @Override
        public Map<Heightmap.Types, long[]> heightmaps() {
            return ImmutableMap.of();
        }

        @Override
        public List<CompoundTag> blockEntities() {
            return ImmutableList.of();
        }

        @Override
        public int[] biomes() {
            return new int[16 * 16];
        }
    }
}
