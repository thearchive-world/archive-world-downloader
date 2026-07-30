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
        return ((long) x & 0xFFFFFFFFL) | ((long) z & 0xFFFFFFFFL) << 32; // ChunkPos.asLong
    }

    @Test
    void emptyReducesToNoRectangles() {
        assertEquals(0, ChunkRectangleReducer.reduce(new long[0]).length);
    }

    @Test
    void aContiguousRowReducesToOneRectangle() {
        long[] row = { at(0, 0), at(1, 0), at(2, 0) };
        int[] rectangles = ChunkRectangleReducer.reduce(row);
        assertEquals(4, rectangles.length); // one rectangle
        assertEquals(0, rectangles[0]); // minX
        assertEquals(0, rectangles[1]); // minZ
        assertEquals(2, rectangles[2]); // maxX
        assertEquals(0, rectangles[3]); // maxZ
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
                confetti[i++] = at(x * 7, z * 7); // spread so each is its own fine rectangle
            }
        }
        // covered = every other, but none forms a fully-saved cell at this spread, so covered stays empty.
        ChunkRectangleReducer.ToneRectangles tones = ChunkRectangleReducer.coarsenTones(confetti, new long[0], 16);
        assertTrue(tones.covered.length / 4 <= 16, "covered coarse count within the ceiling");
        assertTrue(tones.suspect.length / 4 <= 16, "suspect coarse count within the ceiling");
    }

    @Test
    void coarsenTonesClassifiesFullCoveredAndSuspectCellsDisjointly() {
        // A solid 4x4 chunk block; the left half is covered, the right half suspect. With cap 4 -> perAxis 2,
        // cell = 2, so the four 2x2 cells are all fully saved: the two left cells covered, the two right suspect.
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
        // One fully-saved 4x4 cell (cap 1) with nine of sixteen chunks covered: a covered majority draws covered,
        // so a mostly-covered but fragmented cell does not flip to suspect.
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
        // The same cell with only seven of sixteen covered: a suspect majority draws suspect, not covered.
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
        // Exactly eight of sixteen covered: an even split is not a covered majority, so the cell errs to suspect.
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
        // A cell missing one saved chunk (15 of 16) but a covered majority among the saved (10 covered, 5 suspect)
        // must draw suspect, never covered: the covered hue requires a full cell so it never paints the unsaved slot.
        long[] saved = new long[15];
        long[] covered = new long[10];
        int savedIndex = 0;
        int coveredIndex = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 3 && z == 3) {
                    continue; // leave one slot unsaved so the cell is not fully saved
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
        // A one-wide column three rows tall must coalesce into a single tall rectangle. Two rows cannot tell a
        // downward scan from an upward one; three rows pin that the vertical walk advances through every row.
        long[] column = { at(5, 0), at(5, 1), at(5, 2) };
        assertArrayEquals(new int[] { 5, 0, 5, 2 }, ChunkRectangleReducer.reduce(column));
    }

    @Test
    void reduceCoalescesTheMatchingRunWhenTheLowerRowHasSeveralRuns() {
        // The upper row's single run must coalesce with the identical run in the lower row, not with whichever run
        // happens to be first there. The lower row's leading run sits at a different x, so a first-run shortcut
        // would consume it by mistake and drop its own rectangle.
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
        // Off-origin, x-extent dominant. Off-origin makes subtracting the minimum distinguishable from adding it,
        // and an x-wider-than-tall extent puts the x span in control of the cell size; the exact rectangle corners
        // then pin the cell size and each cell's placement, not merely the cell count. Eight wide by two tall over
        // cap 4 gives per-axis 2 and cell 4, so the strip splits into two 4-wide cells, each partly saved (eight of
        // sixteen slots), so both draw suspect and none covered.
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
        // The mirror of the x-dominant case: a taller-than-wide extent puts the z span in control of the cell size,
        // so the z-axis arithmetic is exercised as the deciding term rather than the one Math.max discards.
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
        // Every saved chunk in the cell is covered, but the cell is not fully saved, so it is neither a covered cell
        // (which demands a full cell) nor a suspect cell (which demands a saved-not-covered chunk): it draws nothing.
        // The suspect test is a strict inequality, so an equal saved and covered count must not tip into suspect.
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
