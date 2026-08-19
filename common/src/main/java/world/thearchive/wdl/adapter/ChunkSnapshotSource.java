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
 * The captured, immutable snapshot of a single chunk: the seam the {@link ChunkCodec} encodes.
 *
 * <p>Why a seam: every public {@code LevelChunk} constructor needs a {@code Level}, and the codec additionally reads
 * light from {@code level.getChunkSource().getLightEngine()}: so exercising the codec against a real headless
 * {@code LevelChunk} would require standing up the abstract {@code Level}/{@code ChunkSource}/{@code LevelLightEngine},
 * exactly the heavy harness the design avoids. Instead the codec consumes this snapshot. In the live mod the snapshot
 * is captured from a {@code LevelChunk} + its {@code Level} on the client main thread; in tests a synthetic fixture
 * implements it directly, so the headless round-trip genuinely drives the mod's encode slice, while the thin
 * {@code LevelChunk -> snapshot} light-reading adapter is not exercised headless.
 *
 * <p>All accessors must return data already detached from any live game structure (section copies, cloned
 * light/heightmap arrays, block-entity NBT) so nothing mutable crosses to the async IO worker.
 */
public interface ChunkSnapshotSource {
    ChunkPos chunkPos();

    /** Index of the lowest section (vanilla {@code yPos}); e.g. 0 for a 0..256 overworld. */
    int minSectionY();

    /** Game time stamped as {@code LastUpdate}. */
    long gameTime();

    long inhabitedTime();

    /** The chunk's persisted status (FULL for captured client chunks); drives {@code heightmapsAfter()}. */
    ChunkStatus status();

    /** Whether the captured light is authoritative for this chunk; drives {@code isLightOn}. */
    boolean lightCorrect();

    /**
     * Heightmaps present on the chunk, keyed by type (raw packed {@code long[]}). May include client-irrelevant entries
     * (e.g. a zeroed {@code OCEAN_FLOOR}); the codec selects which to persist.
     */
    Map<Heightmap.Types, long[]> heightmaps();

    /** Per-section snapshot: a section copy plus the captured block/sky light layers (either may be null). */
    List<SectionData> sections();

    /** Block-entity NBT in saved form (empty for synthetic terrain fixtures). */
    List<CompoundTag> blockEntities();

    /**
     * Per-chunk biome registry ids (vanilla {@code Level.Biomes}); below 1.18 biomes are per chunk, not per section.
     */
    int[] biomes();

    /**
     * One captured section: its {@code Y} index, a detached section copy (null for a light-only section outside the
     * chunk's own range), and the block/sky light layers (each null when the layer is empty or the column is unlit).
     * Below the 1.21.2 chunk-serialization cut vanilla has no {@code SerializableChunkData.SectionData} record, so the
     * snapshot carries the band-stable section pieces the encoder writes directly.
     */
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
