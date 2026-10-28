// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ChunkCodec;
import world.thearchive.wdl.adapter.ChunkSnapshotSource;
import world.thearchive.wdl.testsupport.TestRegistries;

class ChunkCodecImplTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    private static ChunkSnapshotSource emptyOverworldChunk() {
        return new TestSnapshot(new ChunkPos(0, 0), -4, 0L, 0L, ChunkStatus.FULL, false,
                Map.of(), List.of(), List.of());
    }

    @Test
    void overworldChunkCarriesSynthesizedBlendingData() {
        RegistryAccess registries = TestRegistries.frozen();

        CompoundTag tag = codec.encode(emptyOverworldChunk(), registries, true);

        CompoundTag blending = tag.getCompound("blending_data");
        assertEquals(-4, (blending.contains("min_section") ? blending.getInt("min_section") : Integer.MIN_VALUE),
                "the fix's -64 block floor as a section");
        assertEquals(20, (blending.contains("max_section") ? blending.getInt("max_section") : Integer.MIN_VALUE),
                "the fix's 320 block ceiling as a section");
        assertFalse(blending.getAllKeys().contains("heights"), "heights are left empty for MC to fill lazily");
    }

    @Test
    void aChunkWithoutBlendingStaysBlendingFree() {
        RegistryAccess registries = TestRegistries.frozen();

        CompoundTag tag = codec.encode(emptyOverworldChunk(), registries, false);

        assertTrue(tag.getCompound("blending_data").isEmpty(), "no blending marker, so the output is byte-unchanged");
    }

    private record TestSnapshot(ChunkPos chunkPos, int minSectionY, long gameTime, long inhabitedTime,
            ChunkStatus status, boolean lightCorrect, Map<Heightmap.Types, long[]> heightmaps,
            List<SerializableChunkData.SectionData> sections, List<CompoundTag> blockEntities)
            implements ChunkSnapshotSource {}
}
