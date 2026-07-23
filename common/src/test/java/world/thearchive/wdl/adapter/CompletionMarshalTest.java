// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class CompletionMarshalTest {
    // An inline executor: the marshal is meant to run on Minecraft-as-Executor; Runnable::run models the
    // game thread running the task without a real client.
    private static final Executor inline = Runnable::run;

    @Test
    void runsThePokeOnNormalCompletion() {
        CompletableFuture<String> future = new CompletableFuture<>();
        int[] ran = { 0 };
        CompletionMarshal.scheduleCompletionPoke(future, inline, () -> ran[0]++);
        assertEquals(0, ran[0]);
        future.complete("done");
        assertEquals(1, ran[0]);
    }

    @Test
    void runsThePokeOnExceptionalCompletion() {
        CompletableFuture<String> future = new CompletableFuture<>();
        int[] ran = { 0 };
        CompletionMarshal.scheduleCompletionPoke(future, inline, () -> ran[0]++);
        future.completeExceptionally(new IllegalStateException("boom"));
        assertEquals(1, ran[0]);
    }

    @Test
    void runsThePokeWhenTheFutureIsAlreadyComplete() {
        CompletableFuture<String> future = CompletableFuture.completedFuture("done");
        int[] ran = { 0 };
        CompletionMarshal.scheduleCompletionPoke(future, inline, () -> ran[0]++);
        assertEquals(1, ran[0]);
    }

    @Test
    void routesThePokeThroughTheSuppliedExecutor() {
        CompletableFuture<String> future = new CompletableFuture<>();
        int[] scheduled = { 0 };
        Executor recording = task -> {
            scheduled[0]++;
            task.run();
        };
        int[] ran = { 0 };
        CompletionMarshal.scheduleCompletionPoke(future, recording, () -> ran[0]++);
        future.complete("done");
        assertEquals(1, scheduled[0]);
        assertEquals(1, ran[0]);
    }
}
