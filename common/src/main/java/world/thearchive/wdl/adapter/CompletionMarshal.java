// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CompletionMarshal {
    private CompletionMarshal() {}

    /**
     * Submit {@code poke} to {@code mainThread} when {@code future} completes, normally or exceptionally. The body only
     * schedules the poke; it must not read the future's result or any shared state off the completing thread.
     *
     * <p>Two caveats follow from {@code mainThread} being an arbitrary {@link Executor}. One that runs a submission
     * inline, as the Minecraft client can when the caller is already on the game thread, runs the poke on the
     * completing stack rather than off the tick, so attach the marshal before anything can complete {@code future}. And
     * a rejected submission drops the poke with nothing logged, since the failure lands in a future no caller observes;
     * the Minecraft client never rejects, its queue being unbounded with no shutdown state.
     */
    public static void scheduleCompletionPoke(CompletableFuture<?> future, Executor mainThread, Runnable poke) {
        future.whenComplete((result, error) -> mainThread.execute(poke));
    }
}
