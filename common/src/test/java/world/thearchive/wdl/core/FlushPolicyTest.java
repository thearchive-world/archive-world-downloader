// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pure keep-hot flush decision, unit-tested MC-free: a captured chunk is flushed to disk (and dropped from the
 * in-memory buffer) once it is farther than the keep-hot radius from the player, measured as the square (Chebyshev)
 * chunk distance that matches the square render-distance capture region.
 */
class FlushPolicyTest {
    @Test
    void aChunkInsideTheKeepHotRadiusIsNotEligible() {
        assertFalse(FlushPolicy.shouldFlush(5, 5, 0, 0, 8), "distance 5 is within keep-hot 8, so keep it buffered");
    }

    @Test
    void aChunkBeyondTheKeepHotRadiusIsEligible() {
        assertTrue(FlushPolicy.shouldFlush(9, 0, 0, 0, 8), "distance 9 exceeds keep-hot 8, so flush it");
    }

    @Test
    void theKeepHotBoundaryIsInclusiveSoExactlyAtTheRadiusIsKept() {
        assertFalse(FlushPolicy.shouldFlush(8, 0, 0, 0, 8), "distance equal to keep-hot stays buffered");
    }

    @Test
    void distanceIsChebyshevAndHandlesNegativeCoordinates() {
        assertEquals(10, FlushPolicy.chunkDistance(-4, 3, 6, -7), "max(|-4-6|, |3-(-7)|) = max(10, 10)");
        assertTrue(FlushPolicy.shouldFlush(-20, 0, 0, 0, 8), "far negative coords flush like positive ones");
    }

    @Test
    void distanceSubtractsTheCoordinatesOnEachAxisNotAdds() {
        // Distinct non-zero endpoints on both axes so a + in place of - changes the max on each: the correct
        // max(|5-2|, |3-1|) = 3, while adding either pair gives max(7, 2) or max(3, 4), never 3.
        assertEquals(3, FlushPolicy.chunkDistance(5, 3, 2, 1));
    }
}
