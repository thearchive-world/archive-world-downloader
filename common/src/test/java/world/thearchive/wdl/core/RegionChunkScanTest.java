// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionChunkScanTest {
    // Write an r.<rx>.<rz>.mca whose 4 KB offset header marks each (localX, localZ) slot present. A real region
    // file has 8 KB of header (offsets then timestamps) plus chunk data; only the offset table matters here.
    private static void writeRegion(Path directory, int regionX, int regionZ, int[][] presentLocals)
            throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8192);
        for (int[] local : presentLocals) {
            int index = (local[0] & 31) + (local[1] & 31) * 32;
            header.putInt(index * Integer.BYTES, 0x00000201); // any non-zero location entry means the chunk exists
        }
        Files.write(directory.resolve("r." + regionX + "." + regionZ + ".mca"), header.array());
    }

    private static boolean contains(long[] values, long target) {
        for (long value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    @Test
    void missingDirectoryIsEmpty(@TempDir Path directory) {
        assertEquals(0, RegionChunkScan.presentChunks(directory.resolve("region")).length);
    }

    @Test
    void readsPresentChunksAcrossRegionFiles(@TempDir Path directory) throws IOException {
        writeRegion(directory, 0, 0, new int[][] { { 0, 0 }, { 1, 2 } });
        writeRegion(directory, -1, 0, new int[][] { { 31, 0 } }); // negative region coordinate
        long[] present = RegionChunkScan.presentChunks(directory);
        assertEquals(3, present.length);
        assertTrue(contains(present, RegionMath.chunkAsLong(0, 0)));
        assertTrue(contains(present, RegionMath.chunkAsLong(1, 2))); // slot index 1 + 2*32 = 65
        assertTrue(contains(present, RegionMath.chunkAsLong(-1, 0))); // r.-1.0 slot 31 maps to chunk x -1
    }

    @Test
    void readsTheLastHeaderSlotAndSkipsAnEmptyRegionFile(@TempDir Path directory) throws IOException {
        writeRegion(directory, 0, 0, new int[][] { { 31, 31 } }); // slot index 1023, the last offset-table entry
        Files.write(directory.resolve("r.1.1.mca"), new byte[0]);
        long[] present = RegionChunkScan.presentChunks(directory);
        assertEquals(1, present.length);
        assertTrue(contains(present, RegionMath.chunkAsLong(31, 31)));
    }

    @Test
    void absentSlotsAndNonRegionFilesAreIgnored(@TempDir Path directory) throws IOException {
        writeRegion(directory, 0, 0, new int[][] { { 5, 5 } });
        Files.write(directory.resolve("notes.txt"), new byte[] { 1, 2, 3 });
        long[] present = RegionChunkScan.presentChunks(directory);
        assertEquals(1, present.length);
        assertTrue(contains(present, RegionMath.chunkAsLong(5, 5)));
        assertFalse(contains(present, RegionMath.chunkAsLong(0, 0))); // slot (0,0) was left zero, so it is absent
    }
}
