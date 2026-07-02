// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two orthogonal capabilities the three-state recapture mode gates: keeping the loaded area current as it changes
 * (hot re-capture), and overwriting an already-downloaded area when the player revisits it. OFF is snapshot-once,
 * NEARBY keeps the working area live but freezes each area once left, and EVERYWHERE also overwrites on revisit.
 */
class RecaptureModeTest {
    @Test
    void offDoesNeither() {
        assertFalse(RecaptureMode.OFF.refreshesHotChunks(), "OFF is snapshot-once, no hot re-capture");
        assertFalse(RecaptureMode.OFF.overwritesRevisitedChunks(), "OFF never overwrites a revisited area");
    }

    @Test
    void nearbyRefreshesTheHotAreaButFreezesWhatIsLeft() {
        assertTrue(RecaptureMode.NEARBY.refreshesHotChunks(), "NEARBY keeps the loaded area current");
        assertFalse(RecaptureMode.NEARBY.overwritesRevisitedChunks(),
                "NEARBY freezes each area once left, so a revisit does not re-buffer it");
    }

    @Test
    void everywhereDoesBoth() {
        assertTrue(RecaptureMode.EVERYWHERE.refreshesHotChunks(), "EVERYWHERE keeps the loaded area current");
        assertTrue(RecaptureMode.EVERYWHERE.overwritesRevisitedChunks(),
                "EVERYWHERE overwrites an already-downloaded area on revisit (current-world-wins)");
    }
}
