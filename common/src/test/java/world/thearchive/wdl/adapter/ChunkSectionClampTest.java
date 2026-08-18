// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
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
 * A 26.x server reached through a downgrade proxy (ViaBackwards) sends a 1.17.1 client sections outside the 0..256
 * column. A block section outside 0..15 crashes vanilla's unchecked {@code sections[index]} on load, so the codec must
 * drop everything outside the -1..16 light column and keep block data only inside 0..15.
 */
class ChunkSectionClampTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    @Test
    void sectionsOutsideThe117ColumnAreDroppedSoTheSaveLoads() {
        RegistryAccess registries = TestRegistries.frozen();
        List<ChunkSnapshotSource.SectionData> sections = new ArrayList<>();
        sections.add(new ChunkSnapshotSource.SectionData(-4, blockSection(-4), null, null)); // below the pad, dropped
        sections.add(new ChunkSnapshotSource.SectionData(-1, null, null, filledLight())); // light pad, kept
        sections.add(new ChunkSnapshotSource.SectionData(0, blockSection(0), null, null)); // in range, kept
        sections.add(new ChunkSnapshotSource.SectionData(15, blockSection(15), null, null)); // in range, kept
        sections.add(new ChunkSnapshotSource.SectionData(16, null, null, filledLight())); // light pad, kept
        sections.add(new ChunkSnapshotSource.SectionData(17, blockSection(17), null, null)); // above the pad, dropped

        CompoundTag level = codec.encode(new ClampSnapshot(sections), registries, false).getCompound("Level");

        Set<Integer> writtenY = new HashSet<>();
        Set<Integer> withBlockData = new HashSet<>();
        for (Tag sectionTag : level.getList("Sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) sectionTag;
            int y = section.getByte("Y");
            writtenY.add(y);
            if (section.contains("BlockStates")) {
                withBlockData.add(y);
            }
        }
        assertEquals(Set.of(-1, 0, 15, 16), writtenY, "only the -1..16 light column survives; -4 and 17 are dropped");
        assertEquals(Set.of(0, 15), withBlockData, "block data is written only inside the 0..15 block range");
    }

    private static LevelChunkSection blockSection(int sectionY) {
        LevelChunkSection section = new LevelChunkSection(sectionY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        return section;
    }

    private static DataLayer filledLight() {
        return new DataLayer(SyntheticChunks.lightFill((byte) 15));
    }

    private record ClampSnapshot(List<ChunkSnapshotSource.SectionData> sections) implements ChunkSnapshotSource {
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
            return ChunkStatus.FULL;
        }

        @Override
        public boolean lightCorrect() {
            return false;
        }

        @Override
        public Map<Heightmap.Types, long[]> heightmaps() {
            return Map.of();
        }

        @Override
        public List<CompoundTag> blockEntities() {
            return List.of();
        }

        @Override
        public int[] biomes() {
            return new int[1024];
        }
    }
}
