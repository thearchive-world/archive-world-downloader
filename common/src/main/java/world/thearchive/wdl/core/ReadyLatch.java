// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A one-shot readiness latch: run a callback now if ready, else stash it until {@link #markReadyAndDrain}. The
 * check-and-stash and the flip-and-drain share one monitor, so a caller on a parallel thread cannot lose its callback
 * to a check-then-act race. MC-free and Java-8-clean; callbacks run outside the lock.
 */
public final class ReadyLatch {
    private final Object lock = new Object();
    private final List<Runnable> pending = new ArrayList<>();
    private boolean ready;

    /** Run {@code runnable} now if ready, else stash it for the next {@link #markReadyAndDrain}. */
    public void runWhenReady(Runnable runnable) {
        synchronized (lock) {
            if (!ready) {
                pending.add(runnable);
                return;
            }
        }
        runnable.run();
    }

    /** Flip to ready and run every stashed callback once, in order. Idempotent after the first call. */
    public void markReadyAndDrain() {
        List<Runnable> toRun;
        synchronized (lock) {
            ready = true;
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable runnable : toRun) {
            runnable.run();
        }
    }
}
