// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReadyLatchTest {
    @Test
    void stashedRunnableRunsOnceAtDrainThenImmediatelyAfter() {
        ReadyLatch latch = new ReadyLatch();
        AtomicInteger runs = new AtomicInteger();
        latch.runWhenReady(runs::incrementAndGet);
        assertEquals(0, runs.get(), "stashed until ready");
        latch.markReadyAndDrain();
        assertEquals(1, runs.get(), "drained once");
        latch.runWhenReady(runs::incrementAndGet);
        assertEquals(2, runs.get(), "after ready, a later runnable runs immediately");
    }

    @Test
    void aSecondDrainDoesNotRerunAlreadyDrainedRunnables() {
        ReadyLatch latch = new ReadyLatch();
        AtomicInteger runs = new AtomicInteger();
        latch.runWhenReady(runs::incrementAndGet);
        latch.markReadyAndDrain();
        latch.markReadyAndDrain();
        assertEquals(1, runs.get(), "a drained runnable never runs again (the pending list is cleared)");
    }

    @Test
    void stashedRunnablesDrainInSubmissionOrder() {
        ReadyLatch latch = new ReadyLatch();
        List<Integer> order = new ArrayList<>();
        latch.runWhenReady(() -> order.add(1));
        latch.runWhenReady(() -> order.add(2));
        latch.markReadyAndDrain();
        assertEquals(List.of(1, 2), order, "callbacks drain in the order they were stashed");
    }
}
