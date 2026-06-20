// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pure nearest-first capture ordering, unit-tested MC-free: under a per-tick encode budget the render-distance
 * square must be filled from the player outward (by Chebyshev ring), so a budget that spills to later ticks does not
 * leave one side of the player perpetually unfilled.
 */
class CaptureOrderTest {
    private static int chebyshev(int dx, int dz) {
        return Math.max(Math.abs(dx), Math.abs(dz));
    }

    @Test
    void radiusZeroIsJustTheCenter() {
        int[] offsets = CaptureOrder.nearestFirstOffsets(0);
        assertEquals(2, offsets.length, "one (dx,dz) pair");
        assertEquals(0, offsets[0]);
        assertEquals(0, offsets[1]);
    }

    @Test
    void theCenterComesFirst() {
        int[] offsets = CaptureOrder.nearestFirstOffsets(3);
        assertEquals(0, offsets[0], "dx of the first cell");
        assertEquals(0, offsets[1], "dz of the first cell");
    }

    @Test
    void ringDistanceNeverDecreases() {
        int[] offsets = CaptureOrder.nearestFirstOffsets(4);
        int previous = -1;
        for (int i = 0; i < offsets.length; i += 2) {
            int ring = chebyshev(offsets[i], offsets[i + 1]);
            assertTrue(ring >= previous, "the order fills outward, never jumping back inward");
            previous = ring;
        }
    }

    @Test
    void coversEveryCellOfTheSquareExactlyOnce() {
        int radius = 3;
        int[] offsets = CaptureOrder.nearestFirstOffsets(radius);
        int side = 2 * radius + 1;
        assertEquals(2 * side * side, offsets.length, "every cell of the (2r+1) square, as (dx,dz) pairs");

        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < offsets.length; i += 2) {
            int dx = offsets[i];
            int dz = offsets[i + 1];
            assertTrue(dx >= -radius && dx <= radius && dz >= -radius && dz <= radius, "within the square");
            assertTrue(seen.add(((long) dx << 32) | (dz & 0xFFFFFFFFL)), "no cell appears twice");
        }
        assertEquals(side * side, seen.size(), "the full square is covered");
    }
}
