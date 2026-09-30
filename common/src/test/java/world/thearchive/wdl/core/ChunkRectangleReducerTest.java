// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ChunkRectangleReducerTest {
    private static long at(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z & 0xFFFFFFFFL) << 32;
    }

    @Test
    void emptyReducesToNoRectangles() {
        assertEquals(0, ChunkRectangleReducer.reduce(new long[0]).length);
    }

    @Test
    void aContiguousRowReducesToOneRectangle() {
        long[] row = { at(0, 0), at(1, 0), at(2, 0) };
        int[] rectangles = ChunkRectangleReducer.reduce(row);
        assertEquals(4, rectangles.length);
        assertEquals(0, rectangles[0]);
        assertEquals(0, rectangles[1]);
        assertEquals(2, rectangles[2]);
        assertEquals(0, rectangles[3]);
    }

    @Test
    void aSolidSquareReducesToOneRectangle() {
        long[] square = { at(0, 0), at(1, 0), at(0, 1), at(1, 1) };
        assertEquals(4, ChunkRectangleReducer.reduce(square).length);
    }

    @Test
    void twoDisjointChunksReduceToTwoRectangles() {
        long[] disjoint = { at(0, 0), at(10, 10) };
        assertEquals(8, ChunkRectangleReducer.reduce(disjoint).length);
    }

    @Test
    void coarsenTonesStaysWithinTheCeiling() {
        long[] confetti = new long[400];
        int i = 0;
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                confetti[i++] = at(x * 7, z * 7);
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(confetti, new long[0], 16);
        assertTrue(tones.covered.length / 4 <= 16, "covered coarse count within the ceiling");
        assertTrue(tones.suspect.length / 4 <= 16, "suspect coarse count within the ceiling");
    }

    @Test
    void coarsenTonesClassifiesFullCoveredAndSuspectCellsDisjointly() {
        long[] saved = new long[16];
        long[] covered = new long[8];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                long pos = at(x, z);
                saved[savedIndex++] = pos;
                if (x < 2) {
                    covered[coveredIndex++] = pos;
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 4);
        assertTrue(tones.covered.length > 0, "the fully-saved-and-covered cells are drawn covered");
        assertTrue(tones.suspect.length > 0, "the fully-saved-not-covered cells are drawn suspect");
        for (int coveredAt = 0; coveredAt < tones.covered.length; coveredAt += 4) {
            for (int suspectAt = 0; suspectAt < tones.suspect.length; suspectAt += 4) {
                boolean sameCell = tones.covered[coveredAt] == tones.suspect[suspectAt]
                        && tones.covered[coveredAt + 1] == tones.suspect[suspectAt + 1];
                assertTrue(!sameCell, "covered and suspect coarse cells must be disjoint");
            }
        }
    }

    @Test
    void coarsenTonesDrawsFullySavedCoveredMajorityCellCovered() {
        long[] saved = new long[16];
        long[] covered = new long[9];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                long pos = at(x, z);
                saved[savedIndex++] = pos;
                if (coveredIndex < 9) {
                    covered[coveredIndex++] = pos;
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 1);
        assertTrue(tones.covered.length > 0, "a fully saved, covered-majority cell draws covered");
        assertEquals(0, tones.suspect.length, "and does not also draw suspect");
    }

    @Test
    void coarsenTonesDrawsFullySavedSuspectMajorityCellSuspect() {
        long[] saved = new long[16];
        long[] covered = new long[7];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                long pos = at(x, z);
                saved[savedIndex++] = pos;
                if (coveredIndex < 7) {
                    covered[coveredIndex++] = pos;
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 1);
        assertTrue(tones.suspect.length > 0, "a fully saved, suspect-majority cell draws suspect");
        assertEquals(0, tones.covered.length, "and does not also draw covered");
    }

    @Test
    void coarsenTonesDrawsAnEvenlySplitFullCellSuspect() {
        long[] saved = new long[16];
        long[] covered = new long[8];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                long pos = at(x, z);
                saved[savedIndex++] = pos;
                if (coveredIndex < 8) {
                    covered[coveredIndex++] = pos;
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 1);
        assertTrue(tones.suspect.length > 0, "an even split draws suspect");
        assertEquals(0, tones.covered.length, "and not covered");
    }

    @Test
    void coarsenTonesKeepsCoveredOffPartiallySavedCell() {
        long[] saved = new long[15];
        long[] covered = new long[10];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 3 && z == 3) {
                    continue;
                }
                long pos = at(x, z);
                saved[savedIndex++] = pos;
                if (coveredIndex < 10) {
                    covered[coveredIndex++] = pos;
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 1);
        assertEquals(0, tones.covered.length, "a partially-saved cell never draws covered");
        assertTrue(tones.suspect.length > 0, "its covered majority still draws suspect");
    }

    @Test
    void reduceCoalescesThreeRowTallRunIntoOneRectangle() {
        long[] column = { at(5, 0), at(5, 1), at(5, 2) };
        assertArrayEquals(new int[] { 5, 0, 5, 2 }, ChunkRectangleReducer.reduce(column));
    }

    @Test
    void reduceCoalescesTheMatchingRunWhenTheLowerRowHasSeveralRuns() {
        long[] chunks = { at(5, 0), at(6, 0), at(0, 1), at(1, 1), at(5, 1), at(6, 1) };
        int[] expected = { 0, 1, 1, 1, 5, 0, 6, 1 };
        assertArrayEquals(expected, normalized(ChunkRectangleReducer.reduce(chunks)));
    }

    @Test
    void coarsenTonesOnEmptySavedReturnsEmptyTones() {
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(new long[0], new long[0], 4);
        assertNotNull(tones);
        assertEquals(0, tones.covered.length);
        assertEquals(0, tones.suspect.length);
    }

    @Test
    void coarsenTonesPlacesCellsByExactCoordinatesOffOriginWiderThanTall() {
        long[] saved = new long[16];
        int savedIndex = 0;
        for (int x = -20; x <= -13; x++) {
            for (int z = -6; z <= -5; z++) {
                saved[savedIndex++] = at(x, z);
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, new long[0], 4);
        assertEquals(0, tones.covered.length, "no cell is fully saved, so none draws covered");
        int[] expectedSuspect = { -20, -6, -17, -3, -16, -6, -13, -3 };
        assertArrayEquals(expectedSuspect, normalized(tones.suspect));
    }

    @Test
    void coarsenTonesPlacesCellsByExactCoordinatesOffOriginTallerThanWide() {
        long[] saved = new long[16];
        int savedIndex = 0;
        for (int x = -6; x <= -5; x++) {
            for (int z = -20; z <= -13; z++) {
                saved[savedIndex++] = at(x, z);
            }
        }
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, new long[0], 4);
        assertEquals(0, tones.covered.length, "no cell is fully saved, so none draws covered");
        int[] expectedSuspect = { -6, -20, -3, -17, -6, -16, -3, -13 };
        assertArrayEquals(expectedSuspect, normalized(tones.suspect));
    }

    @Test
    void coarsenTonesDrawsNothingForFullyCoveredButPartiallySavedCell() {
        long[] saved = { at(0, 0), at(0, 1), at(1, 0) };
        long[] covered = { at(0, 0), at(0, 1), at(1, 0) };
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(saved, covered, 1);
        assertEquals(0, tones.covered.length, "a cell missing a saved slot never draws covered");
        assertEquals(0, tones.suspect.length, "a cell whose every saved chunk is covered draws no suspect");
    }

    private static int[] normalized(int[] rectangles) {
        int[][] byCorner = new int[rectangles.length / 4][4];
        for (int i = 0; i < byCorner.length; i++) {
            System.arraycopy(rectangles, i * 4, byCorner[i], 0, 4);
        }
        Arrays.sort(byCorner, (left, right) -> left[0] != right[0]
                ? Integer.compare(left[0], right[0])
                : Integer.compare(left[1], right[1]));
        int[] out = new int[rectangles.length];
        for (int i = 0; i < byCorner.length; i++) {
            System.arraycopy(byCorner[i], 0, out, i * 4, 4);
        }
        return out;
    }
}
