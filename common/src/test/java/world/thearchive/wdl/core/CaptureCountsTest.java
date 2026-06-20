// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The MC-free live counts: chunks, containers, and the live per-UUID entity tally. */
class CaptureCountsTest {
    @Test
    void carriesChunksContainersAndEntities() {
        CaptureCounts counts = new CaptureCounts(7, 3, 12);
        assertEquals(7, counts.chunks());
        assertEquals(3, counts.containers());
        assertEquals(12, counts.entities());
    }

    @Test
    void emptyIsAllZero() {
        assertEquals(0, CaptureCounts.EMPTY.chunks());
        assertEquals(0, CaptureCounts.EMPTY.containers());
        assertEquals(0, CaptureCounts.EMPTY.entities());
    }
}
