// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The capture state machine, unit-tested MC-free: Idle -> Recording -> Saving -> Idle, capture only while recording,
 * and auto-save on disconnect. The MC-typed capture work is a {@link CaptureController.Session} fake here, so the
 * orchestration is verified without a running game.
 *
 * <p>The save is asynchronous: {@code finish()} only <em>begins</em> the background write, and the controller stays
 * {@code SAVING} until the write reports done. Two routes lead out of it in production: the per-tick
 * {@code isSaveComplete()} poll, and the session's own re-poll once its write completes, which may re-enter
 * {@code tick()} from inside {@code stop()}. The fake here models the tick route only, with a {@link #saveDone} toggle
 * the test flips to signal completion.
 *
 * <p>Wall-clock-dependent behavior (the elapsed timer and the post-save done linger) is driven by a controllable clock
 * so the assertions are deterministic.
 */
class CaptureControllerTest {
    private final long[] now = { 0L };

    private CaptureController controller() {
        return new CaptureController(() -> now[0]);
    }

    private static final class FakeSession implements CaptureController.Session {
        int captures;
        int finishes;
        boolean saveDone; // the test flips this true to signal the background save has completed
        CaptureCounts counts = CaptureCounts.EMPTY;
        CapturedContainers capturedContainers = CapturedContainers.EMPTY;
        RecoveredCoverage recoveredCoverage = RecoveredCoverage.EMPTY;
        SaveStage saveStage = SaveStage.NONE;
        float saveProgress;

        @Override
        public void captureTick() {
            captures++;
        }

        @Override
        public void finish() {
            finishes++; // begins the async save; the fake completes only when saveDone is flipped
        }

        @Override
        public boolean isSaveComplete() {
            return saveDone;
        }

        @Override
        public CaptureCounts counts() {
            return counts;
        }

        @Override
        public CapturedContainers capturedContainers() {
            return capturedContainers;
        }

        @Override
        public RecoveredCoverage recoveredCoverage() {
            return recoveredCoverage;
        }

        @Override
        public SaveStage saveStage() {
            return saveStage;
        }

        @Override
        public float saveProgress() {
            return saveProgress;
        }
    }

    @Test
    void startsIdle() {
        assertEquals(CaptureState.IDLE, controller().state());
    }

    @Test
    void startBeginsRecording() {
        CaptureController controller = controller();
        controller.start(FakeSession::new);
        assertEquals(CaptureState.RECORDING, controller.state());
    }

    @Test
    void ticksCaptureOnlyWhileRecording() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();

        controller.tick(); // idle: nothing captured
        assertEquals(0, session.captures);

        controller.start(() -> session);
        controller.tick();
        controller.tick();
        assertEquals(2, session.captures);
    }

    @Test
    void stopBeginsSavingButStaysSavingUntilTheWriteCompletes() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        controller.stop();

        assertEquals(1, session.finishes, "stop begins the background save");
        assertEquals(CaptureState.SAVING, controller.state(), "the keybind/command returns while the write runs");

        controller.tick(); // the save has not signaled completion yet
        assertEquals(CaptureState.SAVING, controller.state(), "still saving while the background write is in flight");

        session.saveDone = true;
        controller.tick(); // the next tick observes completion on the main thread

        assertEquals(CaptureState.IDLE, controller.state(), "returns to idle once the write completes");
    }

    @Test
    void anAlreadyFinishedSaveReturnsToIdleOnTheVeryNextTick() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveDone = true; // a save already complete when finish() is called
        controller.start(() -> session);

        controller.stop();
        assertEquals(CaptureState.SAVING, controller.state(),
                "this fake's finish() runs no re-poll, so the controller stays saving until the tick");

        controller.tick();
        assertEquals(CaptureState.IDLE, controller.state());
    }

    @Test
    void aFinishedSaveTransitionsExactlyOnceWhenTickIsCalledTwice() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.stop(); // begins the async save; state is SAVING
        assertEquals(CaptureState.SAVING, controller.state());
        session.saveDone = true; // the background write has completed

        controller.tick(); // the poke arrives (poke == controller::tick)
        assertEquals(CaptureState.IDLE, controller.state());
        assertEquals(1, session.finishes);

        controller.tick(); // the retained tick fallback arrives after the poke already transitioned
        assertEquals(CaptureState.IDLE, controller.state());
        assertEquals(1, session.finishes); // still one finish, no double transition
    }

    @Test
    void aSecondStartWhileSavingIsIgnored() {
        CaptureController controller = controller();
        FakeSession first = new FakeSession();
        controller.start(() -> first);
        controller.stop(); // now SAVING, the write still in flight

        controller.start(() -> {
            throw new AssertionError("must not start a second session while a save is in flight");
        });

        assertEquals(CaptureState.SAVING, controller.state());
    }

    @Test
    void aNewCaptureCanStartAfterTheSaveCompletes() {
        CaptureController controller = controller();
        FakeSession first = new FakeSession();
        first.saveDone = true;
        controller.start(() -> first);
        controller.stop();
        controller.tick(); // drains to IDLE

        FakeSession second = new FakeSession();
        controller.start(() -> second);

        assertEquals(CaptureState.RECORDING, controller.state(), "idle again, so a fresh capture starts");
    }

    @Test
    void secondStartWhileRecordingIsIgnored() {
        CaptureController controller = controller();
        FakeSession first = new FakeSession();
        controller.start(() -> first);
        controller.start(() -> {
            throw new AssertionError("must not start a second session while recording");
        });
        controller.tick();
        assertEquals(1, first.captures);
    }

    @Test
    void disconnectWhileRecordingAutoSavesAndDrainsToIdleOnCompletion() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        controller.onDisconnect();

        assertEquals(1, session.finishes, "disconnect begins the background save");
        assertEquals(CaptureState.SAVING, controller.state());

        session.saveDone = true;
        controller.tick();
        assertEquals(CaptureState.IDLE, controller.state());
    }

    @Test
    void disconnectWhileIdleDoesNothing() {
        CaptureController controller = controller();
        controller.onDisconnect();
        assertEquals(CaptureState.IDLE, controller.state());
    }

    @Test
    void aTransferSignalStopsTheRecordingDownloadOnTheNextTick() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.setTransferStopPoll(() -> true);

        controller.tick();

        assertEquals(1, session.finishes, "a transfer stops through the same save path a disconnect uses");
        assertEquals(CaptureState.SAVING, controller.state());
        assertEquals(0, session.captures,
                "the signal is consumed before the capture tick, so the re-entered world is never captured");
    }

    @Test
    void aTransferSignalIsIgnoredWhileIdle() {
        CaptureController controller = controller();
        controller.setTransferStopPoll(() -> true);

        controller.tick(); // no session: must be a no-op, not a crash

        assertEquals(CaptureState.IDLE, controller.state());
    }

    @Test
    void countsDelegateToSessionWhileRecording() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.counts = new CaptureCounts(3, 1, 4);
        controller.start(() -> session);

        assertEquals(3, controller.counts().chunks());
        assertEquals(1, controller.counts().containers());
        assertEquals(4, controller.counts().entities());
    }

    @Test
    void countsAreEmptyWhenIdle() {
        assertSame(CaptureCounts.EMPTY, controller().counts());
    }

    @Test
    void capturedContainersDelegateToSessionWhileRecording() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.capturedContainers = new CapturedContainers(LongSet.of(99L), Set.of(), false);
        controller.start(() -> session);

        assertTrue(controller.capturedContainers().containsBlock(99L));
    }

    @Test
    void capturedContainersAreEmptyWhenIdle() {
        assertSame(CapturedContainers.EMPTY, controller().capturedContainers());
    }

    @Test
    void recoveredCoverageDelegatesToSessionWhileRecording() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.recoveredCoverage = new RecoveredCoverage(LongSet.of(42L));
        controller.start(() -> session);

        assertTrue(controller.recoveredCoverage().contains(42L));
    }

    @Test
    void recoveredCoverageIsEmptyWhenIdle() {
        assertSame(RecoveredCoverage.EMPTY, controller().recoveredCoverage());
    }

    @Test
    void savedChunksIsStableAndNonNull() {
        CaptureController controller = controller();
        SavedChunkIndex index = controller.savedChunks();
        assertNotNull(index, "the overlay always has an index to read, even before any capture");
        assertSame(index, controller.savedChunks(), "one stable reference the off-thread overlay can hold");
    }

    @Test
    void startClearsTheSavedChunkOverlay() {
        CaptureController controller = controller();
        controller.savedChunks().add("minecraft:overworld", 42L);

        controller.start(FakeSession::new);

        assertEquals(0, controller.savedChunks().snapshot("minecraft:overworld").length,
                "a fresh capture starts with an empty overlay, not the prior session's chunks");
    }

    @Test
    void savedChunkOverlayIsClearedWhenTheSaveCompletes() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.savedChunks().add("minecraft:overworld", 42L);

        controller.stop();
        session.saveDone = true;
        controller.tick();

        assertEquals(0, controller.savedChunks().snapshot("minecraft:overworld").length,
                "the overlay is emptied once the save completes, so nothing draws while idle");
    }

    @Test
    void aWriterSideSeedLandingAfterStopDoesNotSurviveIntoIdle() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        controller.stop();
        // The finish drain can be the first flush for the dimension, so the resume overlay seed is queued behind
        // it and repopulates the indexes on the writer thread at some point after stop returns.
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 4, 4, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);
        session.saveDone = true;
        controller.tick();

        assertEquals(0, controller.savedChunks().snapshot("minecraft:overworld").length,
                "the seeded prior coverage outlived the download that seeded it");
        assertEquals(0, controller.coveredChunks().snapshot("minecraft:overworld").length,
                "the seeded prior covered tone outlived the download that seeded it");
        assertFalse(controller.sendRange().isCalibrated("minecraft:overworld"),
                "the seeded prior calibration outlived the download that seeded it");
    }

    @Test
    void theOverlayStoresStayPopulatedWhileTheSaveDrains() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 4, 4, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        controller.stop();

        assertEquals(CaptureState.SAVING, controller.state());
        assertTrue(controller.savedChunks().snapshot("minecraft:overworld").length > 0,
                "clearing at stop is what lets a queued writer-side seed land after the clear");
        assertTrue(controller.coveredChunks().snapshot("minecraft:overworld").length > 0);
        assertTrue(controller.sendRange().isCalibrated("minecraft:overworld"));
    }

    @Test
    void startClearsTheCoveredOverlayAndTheSendRangeEstimate() {
        CaptureController controller = controller();
        controller.coveredChunks().recordTrail("minecraft:overworld", 4, 4, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        controller.start(FakeSession::new);

        assertEquals(0, controller.coveredChunks().snapshot("minecraft:overworld").length,
                "a fresh capture starts with no covered tone, not the prior session's coverage");
        assertFalse(controller.sendRange().isCalibrated("minecraft:overworld"),
                "a new capture recalibrates the send range from scratch");
    }

    @Test
    void coveredOverlayAndSendRangeEstimateAreClearedWhenTheSaveCompletes() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.coveredChunks().recordTrail("minecraft:overworld", 4, 4, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        controller.stop();
        session.saveDone = true;
        controller.tick();

        assertEquals(0, controller.coveredChunks().snapshot("minecraft:overworld").length,
                "the covered tone is emptied once the save completes, so nothing draws while idle");
        assertFalse(controller.sendRange().isCalibrated("minecraft:overworld"),
                "the estimate does not carry into the next capture");
    }

    @Test
    void countsFreezeAtStopThenRefreshToTheWrittenTotalsAtCompletion() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.counts = new CaptureCounts(5, 2, 3);
        controller.start(() -> session);

        controller.stop(); // snapshot the stop-time counts before the live session is torn down
        session.counts = new CaptureCounts(5, 2, 9); // the save drain grows the figures past the freeze

        assertEquals(5, controller.counts().chunks(), "frozen through saving");
        assertEquals(2, controller.counts().containers());
        assertEquals(3, controller.counts().entities());

        session.saveDone = true;
        controller.tick(); // -> IDLE, the done linger begins

        assertEquals(5, controller.counts().chunks(), "the done linger shows the written totals");
        assertEquals(9, controller.counts().entities());
    }

    @Test
    void countsClearOnceIdlePastTheDoneLinger() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.counts = new CaptureCounts(5, 2, 3);
        session.saveDone = true;
        controller.start(() -> session);
        controller.stop();
        controller.tick(); // -> IDLE, done linger begins at now=0

        now[0] = 5_000L;
        assertEquals(5, controller.counts().chunks(), "still held a few seconds into the linger");

        now[0] = 120_000L;
        assertSame(CaptureCounts.EMPTY, controller.counts(), "cleared once idle well past the linger");
    }

    @Test
    void aFreshStartClearsThePriorFrozenCounts() {
        CaptureController controller = controller();
        FakeSession first = new FakeSession();
        first.counts = new CaptureCounts(5, 2, 3);
        first.saveDone = true;
        controller.start(() -> first);
        controller.stop();
        controller.tick(); // -> IDLE, done linger holding (5,2,3)

        FakeSession second = new FakeSession();
        second.counts = new CaptureCounts(1, 0, 0);
        controller.start(() -> second);

        assertEquals(1, controller.counts().chunks(), "the new capture's live counts replace the prior frozen ones");
    }

    @Test
    void elapsedIsZeroWhenIdle() {
        assertEquals(0L, controller().elapsedMillis());
    }

    @Test
    void elapsedTracksWallClockWhileRecording() {
        CaptureController controller = controller();
        now[0] = 1_000L;
        controller.start(FakeSession::new);

        now[0] = 4_000L;
        assertEquals(3_000L, controller.elapsedMillis());
    }

    @Test
    void elapsedFreezesAtStopAndHoldsThroughSavingAndDone() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        now[0] = 1_000L;
        controller.start(() -> session);

        now[0] = 6_000L;
        controller.stop(); // freezes the 5s capture duration

        now[0] = 30_000L;
        assertEquals(5_000L, controller.elapsedMillis(), "the timer holds the capture duration through saving");

        session.saveDone = true;
        controller.tick(); // -> IDLE done linger
        now[0] = 31_000L;
        assertEquals(5_000L, controller.elapsedMillis(), "and through the done linger");

        now[0] = 200_000L;
        assertEquals(0L, controller.elapsedMillis(), "cleared once past the linger");
    }

    @Test
    void saveStageIsNoneAndProgressZeroUnlessSaving() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveStage = SaveStage.WRITING_MAPS; // a session value that must not leak outside SAVING
        session.saveProgress = 0.5f;

        assertEquals(SaveStage.NONE, controller.saveStage(), "none while idle");
        assertEquals(0.0f, controller.saveProgress());

        controller.start(() -> session);
        assertEquals(SaveStage.NONE, controller.saveStage(), "none while recording (no bar during capture)");
        assertEquals(0.0f, controller.saveProgress());
    }

    @Test
    void saveStageAndProgressDelegateToTheSessionWhileSaving() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        session.saveStage = SaveStage.COMPRESSING;
        session.saveProgress = 0.75f;
        controller.stop(); // -> SAVING

        assertEquals(SaveStage.COMPRESSING, controller.saveStage());
        assertEquals(0.75f, controller.saveProgress());

        session.saveDone = true;
        controller.tick(); // -> IDLE
        assertEquals(SaveStage.NONE, controller.saveStage(), "no bar once the save completes");
        assertEquals(0.0f, controller.saveProgress());
    }

    @Test
    void doneLingerAccessorIsPresentOnlyAfterTheSaveCompletesWithinTheHold() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        assertFalse(controller.doneElapsedMillis().isPresent(), "absent while recording");

        controller.stop();
        assertFalse(controller.doneElapsedMillis().isPresent(), "absent while saving");

        session.saveDone = true;
        now[0] = 10_000L;
        controller.tick(); // save completes at now=10_000

        now[0] = 12_500L;
        assertTrue(controller.doneElapsedMillis().isPresent(), "present once the save completes");
        assertEquals(2_500L, controller.doneElapsedMillis().getAsLong(), "elapsed since the save completed");

        now[0] = 200_000L;
        assertFalse(controller.doneElapsedMillis().isPresent(), "absent again once past the hold window");
    }

    @Test
    void restoringFlipIsAtomicFromIdleOnly() {
        CaptureController controller = controller(); // the shipped test factory
        assertTrue(controller.tryBeginRestoring());
        assertEquals(CaptureState.RESTORING, controller.state());
        assertFalse(controller.tryBeginRestoring()); // second flip refused
        // Capture start refuses while RESTORING (the shipped IDLE guard covers it).
        controller.start(FakeSession::new);
        assertEquals(CaptureState.RESTORING, controller.state());
        // stop/onDisconnect are RECORDING-guarded no-ops.
        controller.stop();
        controller.onDisconnect();
        assertEquals(CaptureState.RESTORING, controller.state());
        controller.endRestoring();
        assertEquals(CaptureState.IDLE, controller.state());
    }

    @Test
    void restoringFlipIsRefusedWhileRecording() {
        CaptureController controller = controller();
        controller.start(FakeSession::new);

        assertFalse(controller.tryBeginRestoring(), "a running capture is never disturbed by a restore");
        assertEquals(CaptureState.RECORDING, controller.state());

        controller.endRestoring(); // a stray end is a no-op outside RESTORING
        assertEquals(CaptureState.RECORDING, controller.state());
    }

    @Test
    void doneLingerHoldIsInclusiveAtItsExactBoundary() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveDone = true;
        controller.start(() -> session);
        controller.stop();
        controller.tick(); // save completes at now = 0

        now[0] = 60_000L; // exactly the 60s DONE_LINGER_HOLD_MILLIS: the boundary itself is still within the hold
        assertTrue(controller.doneElapsedMillis().isPresent(), "the done frame still reads at the exact boundary");

        now[0] = 60_001L;
        assertFalse(controller.doneElapsedMillis().isPresent(), "and is gone one millisecond past it");
    }
}
