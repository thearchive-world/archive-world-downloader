// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimingWindowTest {
    @Test
    void doesNotFireBeforeTheWindowFills() {
        TimingWindow window = new TimingWindow(3);
        assertFalse(window.record(1_000L));
        assertFalse(window.record(1_000L));
    }

    @Test
    void firesOnTheSampleThatFillsTheWindow() {
        TimingWindow window = new TimingWindow(3);
        window.record(1_000L);
        window.record(3_000L);
        assertTrue(window.record(2_000L));
        assertEquals(3L, window.count());
        assertEquals(2L, window.averageMicros());
        assertEquals(3L, window.maxMicros());
    }

    @Test
    void resetClearsTheAccumulator() {
        TimingWindow window = new TimingWindow(2);
        window.record(5_000L);
        window.record(5_000L);
        window.reset();
        assertEquals(0L, window.count());
        assertEquals(0L, window.averageMicros());
        assertEquals(0L, window.maxMicros());
    }
}
