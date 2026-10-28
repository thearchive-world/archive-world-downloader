// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * All accessors must return data already detached from any live game structure (section copies, cloned light/heightmap
 * arrays, block-entity NBT) so nothing mutable crosses to the save writer thread.
 */
public interface ChunkSnapshotSource {
    ChunkPos chunkPos();

    int minSectionY();

    /** Game time stamped as {@code LastUpdate}. */
    long gameTime();

    long inhabitedTime();

    ChunkStatus status();

    /** Whether the captured light is authoritative for this chunk. */
    boolean lightCorrect();

    /**
     * Heightmaps present on the chunk, keyed by type (raw packed {@code long[]}). May include client-irrelevant
     * entries; the codec selects which to persist.
     */
    Map<Heightmap.Types, long[]> heightmaps();

    /** Per-section snapshot: a section copy plus the captured block/sky light layers (either may be null). */
    List<SectionData> sections();

    /** Block-entity NBT in saved form. */
    List<CompoundTag> blockEntities();

    /**
     * Per-chunk biome registry ids (vanilla {@code Level.Biomes}).
     */
    int[] biomes();

    static final class SectionData {
        private final int y;
        private final @Nullable LevelChunkSection chunkSection;
        private final @Nullable DataLayer blockLight;
        private final @Nullable DataLayer skyLight;

        public SectionData(int y, @Nullable LevelChunkSection chunkSection, @Nullable DataLayer blockLight,
                @Nullable DataLayer skyLight) {
            this.y = y;
            this.chunkSection = chunkSection;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        public int y() {
            return y;
        }

        public @Nullable LevelChunkSection chunkSection() {
            return chunkSection;
        }

        public @Nullable DataLayer blockLight() {
            return blockLight;
        }

        public @Nullable DataLayer skyLight() {
            return skyLight;
        }
    }
}
