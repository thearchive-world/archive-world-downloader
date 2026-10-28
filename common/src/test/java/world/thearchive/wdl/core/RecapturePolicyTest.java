// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecapturePolicyTest {
    @Test
    void theEditZoneIsTheChebyshevSquareOfTheGivenRadiusAroundTheCenter() {
        assertTrue(RecapturePolicy.isInEditZone(0, 0, 0, 0, 1), "the center is in its own edit zone");
        assertTrue(RecapturePolicy.isInEditZone(1, 1, 0, 0, 1), "a diagonal neighbor is in the 3x3");
        assertTrue(RecapturePolicy.isInEditZone(-1, 1, 0, 0, 1), "negative-offset neighbors count too");
        assertFalse(RecapturePolicy.isInEditZone(2, 0, 0, 0, 1), "two chunks away is outside the 3x3");
    }

    @Test
    void theFloorSliceCoversTheWholeHotSetWithinTheRefreshPeriod() {
        assertEquals(5, RecapturePolicy.floorSliceSize(1369, 15, 20), "ceil(1369 / (15*20)) = 5");
        assertEquals(16, RecapturePolicy.floorSliceSize(4761, 15, 20), "ceil(4761 / (15*20)) = 16");
    }

    @Test
    void theFloorSliceIsAtLeastOneWhenHotAndZeroWhenEmpty() {
        assertEquals(1, RecapturePolicy.floorSliceSize(50, 15, 20), "a small hot set still advances one per tick");
        assertEquals(0, RecapturePolicy.floorSliceSize(0, 15, 20), "nothing buffered means no floor work");
    }

    @Test
    void theFloorSliceNeverDividesByZeroWhenThePeriodIsDegenerate() {
        assertEquals(5, RecapturePolicy.floorSliceSize(100, 0, 20), "period clamped to 1s -> ceil(100/20) = 5");
        assertEquals(5, RecapturePolicy.floorSliceSize(100, -7, 20), "a negative period clamps the same way");
    }

    @Test
    void aCandidateIsReencodedOnlyWhileStillBufferedAndStillLoaded() {
        assertTrue(RecapturePolicy.shouldRecapture(true, true), "a still-hot, still-loaded chunk is re-encoded");
        assertFalse(RecapturePolicy.shouldRecapture(false, true), "a flushed chunk is never revived");
        assertFalse(RecapturePolicy.shouldRecapture(true, false), "an unloaded margin chunk is skipped");
        assertFalse(RecapturePolicy.shouldRecapture(false, false), "neither buffered nor loaded -> skip");
    }
}
