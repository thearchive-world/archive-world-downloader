// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import org.jspecify.annotations.Nullable;

/**
 * A region file holds a 32x32 grid of chunks. Vanilla derives the region coordinate with an arithmetic shift
 * ({@code chunkCoordinate >> 5}) and the in-file slot with a mask ({@code chunkCoordinate & 31});
 * {@link Math#floorDiv(int, int)} / {@link Math#floorMod(int, int)} are the exact integer equivalents for power-of-two
 * 32 and, unlike {@code /} and {@code %}, floor toward negative infinity so negative coordinates land in the correct
 * region (chunk -1 -> region -1, local 31).
 */
final class RegionMath {
    private static final int CHUNKS_PER_REGION_EDGE = 32;

    private RegionMath() {}

    private static int regionX(int chunkX) {
        return Math.floorDiv(chunkX, CHUNKS_PER_REGION_EDGE);
    }

    private static int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, CHUNKS_PER_REGION_EDGE);
    }

    public static String regionFileName(int chunkX, int chunkZ) {
        return "r." + regionX(chunkX) + "." + regionZ(chunkZ) + ".mca";
    }

    public static int offsetIndex(int chunkX, int chunkZ) {
        int localX = Math.floorMod(chunkX, CHUNKS_PER_REGION_EDGE);
        int localZ = Math.floorMod(chunkZ, CHUNKS_PER_REGION_EDGE);
        return localX + localZ * CHUNKS_PER_REGION_EDGE;
    }

    /**
     * The packed {@code long} key for a chunk, mirroring the game's own chunk packing
     * ({@code x & 0xFFFFFFFF | (z & 0xFFFFFFFF) << 32}), so a key packed here is interchangeable with a position the
     * game packs the same way.
     */
    public static long chunkAsLong(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    public static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

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

    public static long chunkKeyAt(int regionX, int regionZ, int offsetIndex) {
        int chunkX = regionX * CHUNKS_PER_REGION_EDGE + offsetIndex % CHUNKS_PER_REGION_EDGE;
        int chunkZ = regionZ * CHUNKS_PER_REGION_EDGE + offsetIndex / CHUNKS_PER_REGION_EDGE;
        return chunkAsLong(chunkX, chunkZ);
    }
}
