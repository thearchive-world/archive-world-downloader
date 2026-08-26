// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
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

    /**
     * Whether the captured light is authoritative for this chunk; each band's encode maps it onto its own on-disk
     * light-validity key ({@code isLightOn} at the newer bands, the pre-Flattening boolean {@code LightPopulated}
     * here).
     */
    boolean lightCorrect();

    /**
     * The chunk's heightmap. Below 1.13 there is no keyed {@code Heightmap.Types}/{@code ChunkStatus}: a chunk carries
     * exactly one flat heightmap, the raw {@code int[256]} a pre-Flattening save writes verbatim under
     * {@code HeightMap}.
     */
    int[] heightmaps();

    /** Per-section snapshot: a section copy plus the captured block/sky light layers (either may be null). */
    List<SectionData> sections();

    /** Block-entity NBT in saved form (empty for synthetic terrain fixtures). */
    List<NBTTagCompound> blockEntities();

    /**
     * Per-chunk biome ids (vanilla {@code Level.Biomes}), widened to {@code int} for the interface; below 1.13 the
     * on-disk array is a {@code byte[256]}, one id per column, which the encode narrows back down.
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
        private final @Nullable ExtendedBlockStorage chunkSection;
        private final @Nullable NibbleArray blockLight;
        private final @Nullable NibbleArray skyLight;

        public SectionData(int y, @Nullable ExtendedBlockStorage chunkSection, @Nullable NibbleArray blockLight,
                @Nullable NibbleArray skyLight) {
            this.y = y;
            this.chunkSection = chunkSection;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        public int y() {
            return y;
        }

        public @Nullable ExtendedBlockStorage chunkSection() {
            return chunkSection;
        }

        public @Nullable NibbleArray blockLight() {
            return blockLight;
        }

        public @Nullable NibbleArray skyLight() {
            return skyLight;
        }
    }
}
