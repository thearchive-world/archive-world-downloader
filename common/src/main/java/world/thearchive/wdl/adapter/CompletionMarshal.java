// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The single seam that hands a background completion back to the game thread. A worker thread completes a future; this
 * submits a poke to an executor the moment it does, on both normal and exceptional completion, so a caller never needs
 * a second success-only path.
 *
 * <p>Callers pass the Minecraft client, whose task queue drains every frame outside the tick loop, so the poke arrives
 * even while the game tick is suspended (a paused replay). That off-tick arrival is the whole point of the seam, but it
 * is a property of that executor rather than of this class, which promises only the submission. Kept MC-free so it
 * unit-tests with an inline executor; lives here beside its capture callers rather than in core.
 */
public final class CompletionMarshal {
    private CompletionMarshal() {}

    /**
     * Submit {@code poke} to {@code mainThread} when {@code future} completes, normally or exceptionally. The body only
     * schedules the poke; it must not read the future's result or any shared state off the completing thread.
     *
     * <p>Two caveats follow from {@code mainThread} being an arbitrary {@link Executor}. One that runs a submission
     * inline, as the Minecraft client does when the caller is already on the game thread, runs the poke on the
     * completing stack rather than off the tick, so attach the marshal before anything can complete {@code future}. And
     * a rejected submission drops the poke with nothing logged, since the failure lands in a future no caller observes;
     * the Minecraft client never rejects, its queue being unbounded with no shutdown state.
     */
    public static void scheduleCompletionPoke(CompletableFuture<?> future, Executor mainThread, Runnable poke) {
        future.whenComplete((result, error) -> mainThread.execute(poke));
    }
}
