// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import org.jspecify.annotations.Nullable;

/**
 * Pure {@code ChunkPos} -> region-file mapping. Version-agnostic core: imports no {@code net.minecraft.*} type
 * (CI-enforced) so it stays cherry-pickable across era-band branches.
 *
 * <p>A region file holds a 32x32 grid of chunks. Vanilla derives the region coordinate with an arithmetic shift
 * ({@code chunkCoordinate >> 5}) and the in-file slot with a mask ({@code chunkCoordinate & 31});
 * {@link Math#floorDiv(int, int)} / {@link Math#floorMod(int, int)} are the exact integer equivalents for power-of-two
 * 32 and, unlike {@code /} and {@code %}, floor toward negative infinity so negative coordinates land in the correct
 * region (chunk -1 -> region -1, local 31).
 */
final class RegionMath {
    /** A region file spans this many chunks on each axis. */
    private static final int CHUNKS_PER_REGION_EDGE = 32;

    private RegionMath() {}

    /** Region X for a chunk X (vanilla {@code ChunkPos.getRegionX()} = {@code chunkX >> 5}). */
    private static int regionX(int chunkX) {
        return Math.floorDiv(chunkX, CHUNKS_PER_REGION_EDGE);
    }

    /** Region Z for a chunk Z (vanilla {@code ChunkPos.getRegionZ()} = {@code chunkZ >> 5}). */
    private static int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, CHUNKS_PER_REGION_EDGE);
    }

    /** The Anvil region file name (e.g. {@code r.0.-1.mca}) for the region containing this chunk. */
    public static String regionFileName(int chunkX, int chunkZ) {
        return "r." + regionX(chunkX) + "." + regionZ(chunkZ) + ".mca";
    }

    /**
     * The chunk's slot within its region file's 1024-entry header (vanilla {@code RegionFile} offset index =
     * {@code localX + localZ * 32}).
     */
    public static int offsetIndex(int chunkX, int chunkZ) {
        int localX = Math.floorMod(chunkX, CHUNKS_PER_REGION_EDGE);
        int localZ = Math.floorMod(chunkZ, CHUNKS_PER_REGION_EDGE);
        return localX + localZ * CHUNKS_PER_REGION_EDGE;
    }

    /**
     * The packed {@code long} key for a chunk, mirroring vanilla {@code ChunkPos.asLong}
     * ({@code x & 0xFFFFFFFF | (z & 0xFFFFFFFF) << 32}), so a key packed here is interchangeable with a position the
     * game packs the same way.
     */
    public static long chunkAsLong(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /** The chunk X in a {@link #chunkAsLong} key, mirroring vanilla {@code ChunkPos.getX} ({@code (int) key}). */
    public static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    /**
     * The chunk Z in a {@link #chunkAsLong} key, mirroring vanilla {@code ChunkPos.getZ} ({@code (int) (key >> 32)}).
     */
    public static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /**
     * The {@code {regionX, regionZ}} encoded in an {@code r.<rx>.<rz>.mca} file name, or {@code null} if the name is
     * not that shape. Inverse of {@link #regionFileName}.
     */
    public static int @Nullable [] regionFileCoordinates(String fileName) {
        String[] parts = fileName.split("\\.");
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The {@link #chunkAsLong} key for the chunk in offset-table slot {@code offsetIndex} (a {@code 0..1023} header
     * index) of the region at {@code (regionX, regionZ)}. Inverse of {@link #offsetIndex} composed with
     * {@link #chunkAsLong}.
     */
    public static long chunkKeyAt(int regionX, int regionZ, int offsetIndex) {
        int chunkX = regionX * CHUNKS_PER_REGION_EDGE + offsetIndex % CHUNKS_PER_REGION_EDGE;
        int chunkZ = regionZ * CHUNKS_PER_REGION_EDGE + offsetIndex / CHUNKS_PER_REGION_EDGE;
        return chunkAsLong(chunkX, chunkZ);
    }
}
