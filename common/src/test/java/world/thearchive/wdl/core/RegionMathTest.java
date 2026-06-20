// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pure region-file math. Mirrors vanilla: a region is a 32x32 block of chunks ({@code ChunkPos.getRegionX/Z} =
 * {@code chunkCoordinate >> 5}), the chunk's slot within the region file is {@code localX + localZ*32}
 * ({@code RegionFile} offset index), and negative coordinates must floor toward negative infinity (so chunk -1 lives in
 * region -1, local 31).
 */
class RegionMathTest {
    @Test
    void mapsChunkToRegionFileName() {
        assertEquals("r.0.0.mca", RegionMath.regionFileName(0, 0));
        assertEquals("r.-1.-1.mca", RegionMath.regionFileName(-1, -1)); // negative floors to -1
        assertEquals("r.0.0.mca", RegionMath.regionFileName(31, 31));   // boundary, same region
        assertEquals("r.1.0.mca", RegionMath.regionFileName(32, 0));    // wrap 31 -> 32 into region 1
    }

    @Test
    void mapsChunkToOffsetIndex() {
        assertEquals(0, RegionMath.offsetIndex(0, 0));
        assertEquals(1023, RegionMath.offsetIndex(31, 31)); // 31 + 31*32
        assertEquals(0, RegionMath.offsetIndex(32, 0));     // local wrap 32 -> 0
        assertEquals(0, RegionMath.offsetIndex(-32, 0));    // negative local wrap -32 -> 0
    }

    @Test
    void packsChunkKeyLikeVanillaAsLong() {
        // Bit layout pinned to vanilla ChunkPos.asLong: x & 0xFFFFFFFF | (z & 0xFFFFFFFF) << 32.
        assertEquals(0L, RegionMath.chunkAsLong(0, 0));
        assertEquals(0x0000000200000001L, RegionMath.chunkAsLong(1, 2));
        assertEquals(0xFFFFFFFFL, RegionMath.chunkAsLong(-1, 0));         // x = -1 fills the low word
        assertEquals(0xFFFFFFFF00000000L, RegionMath.chunkAsLong(0, -1)); // z = -1 fills the high word
    }

    @Test
    void parsesRegionFileName() {
        assertArrayEquals(new int[] { 0, 0 }, RegionMath.regionFileCoordinates("r.0.0.mca"));
        assertArrayEquals(new int[] { -1, 2 }, RegionMath.regionFileCoordinates("r.-1.2.mca"));
        assertNull(RegionMath.regionFileCoordinates("notes.txt"));
        assertNull(RegionMath.regionFileCoordinates("r.x.0.mca")); // non-numeric coordinate
    }

    @Test
    void regionFileCoordsInvertsRegionFileName() {
        assertArrayEquals(new int[] { 0, 0 }, RegionMath.regionFileCoordinates(RegionMath.regionFileName(31, 31)));
        assertArrayEquals(new int[] { -1, -1 }, RegionMath.regionFileCoordinates(RegionMath.regionFileName(-1, -1)));
        assertArrayEquals(new int[] { 1, 0 }, RegionMath.regionFileCoordinates(RegionMath.regionFileName(32, 0)));
    }

    @Test
    void chunkKeyDecodersInvertChunkAsLong() {
        // Decoders pinned to vanilla ChunkPos.getX/getZ: x in the low word ((int) key), z in the high
        // word ((int) (key >> 32)). Negative coordinates round-trip through the sign-extending casts.
        assertEquals(0, RegionMath.chunkX(RegionMath.chunkAsLong(0, 0)));
        assertEquals(0, RegionMath.chunkZ(RegionMath.chunkAsLong(0, 0)));
        assertEquals(1, RegionMath.chunkX(RegionMath.chunkAsLong(1, 2)));
        assertEquals(2, RegionMath.chunkZ(RegionMath.chunkAsLong(1, 2)));
        assertEquals(-1, RegionMath.chunkX(RegionMath.chunkAsLong(-1, 0)));
        assertEquals(-1, RegionMath.chunkZ(RegionMath.chunkAsLong(0, -1)));
        assertEquals(-100, RegionMath.chunkX(RegionMath.chunkAsLong(-100, 50)));
        assertEquals(50, RegionMath.chunkZ(RegionMath.chunkAsLong(-100, 50)));
    }

    @Test
    void decodesChunkKeyFromRegionAndOffsetSlot() {
        assertEquals(RegionMath.chunkAsLong(0, 0), RegionMath.chunkKeyAt(0, 0, 0));
        assertEquals(RegionMath.chunkAsLong(1, 2), RegionMath.chunkKeyAt(0, 0, 65)); // slot 1 + 2*32
        assertEquals(RegionMath.chunkAsLong(-1, 0), RegionMath.chunkKeyAt(-1, 0, 31)); // r.-1.0 slot 31 -> chunk x -1
        assertEquals(RegionMath.chunkAsLong(32, 0), RegionMath.chunkKeyAt(1, 0, 0));
        // A non-zero regionZ must scale by the edge (regionZ*32), never divide: r.0.1 slot 0 -> chunk z 32.
        assertEquals(RegionMath.chunkAsLong(0, 32), RegionMath.chunkKeyAt(0, 1, 0));
        assertEquals(RegionMath.chunkAsLong(64, 96), RegionMath.chunkKeyAt(2, 3, 0)); // both region axes scale
    }
}
