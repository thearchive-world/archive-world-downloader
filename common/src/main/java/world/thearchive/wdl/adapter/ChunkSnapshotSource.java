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
 * All accessors must return data already detached from any live game structure (section copies, cloned light/heightmap
 * arrays, block-entity NBT) so nothing mutable crosses to the save writer thread.
 */
public interface ChunkSnapshotSource {
    ChunkPos chunkPos();

    int minSectionY();

    /** Game time stamped as {@code LastUpdate}. */
    long gameTime();

    long inhabitedTime();

    /**
     * Whether the captured light is authoritative for this chunk.
     */
    boolean lightCorrect();

    /**
     * Below 1.13 there is no keyed {@code Heightmap.Types}/{@code ChunkStatus}: a chunk carries exactly one flat
     * heightmap, the raw {@code int[256]} a pre-Flattening save writes verbatim under {@code HeightMap}.
     */
    int[] heightmaps();

    /** Per-section snapshot: a section copy plus the captured block/sky light layers (either may be null). */
    List<SectionData> sections();

    /** Block-entity NBT in saved form. */
    List<NBTTagCompound> blockEntities();

    /**
     * Per-chunk biome ids (vanilla {@code Level.Biomes}), widened to {@code int} for the interface; below 1.13 the
     * on-disk array is a {@code byte[256]}, one id per column, which the encode narrows back down.
     */
    int[] biomes();

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
