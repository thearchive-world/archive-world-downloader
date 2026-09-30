// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class CaptureControllerTest {
    private final long[] now = { 0L };

    private CaptureController controller() {
        return new CaptureController(() -> now[0]);
    }

    private static class FakeSession implements CaptureController.Session {
        int captures;
        int finishes;
        boolean saveDone;
        CaptureCounts counts = CaptureCounts.EMPTY;
        CapturedContainers capturedContainers = CapturedContainers.EMPTY;
        RecoveredCoverage recoveredCoverage = RecoveredCoverage.EMPTY;
        CaptureToggles latchedToggles = CaptureToggles.from(WdlConfig.DEFAULTS);
        SaveStage saveStage = SaveStage.NONE;
        float saveProgress;

        int holds;
        int releases;

        @Override
        public void captureTick() {
            captures++;
        }

        @Override
        public void holdWriterEncoding() {
            holds++;
        }

        @Override
        public void releaseWriterEncoding() {
            releases++;
        }

        @Override
        public void finish() {
            finishes++;
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
        public CaptureToggles latchedToggles() {
            return latchedToggles;
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

        controller.tick();
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

        controller.tick();
        assertEquals(CaptureState.SAVING, controller.state(), "still saving while the background write is in flight");

        session.saveDone = true;
        controller.tick();

        assertEquals(CaptureState.IDLE, controller.state(), "returns to idle once the write completes");
    }

    @Test
    void anAlreadyFinishedSaveReturnsToIdleOnTheVeryNextTick() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveDone = true;
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
        controller.stop();
        assertEquals(CaptureState.SAVING, controller.state());
        session.saveDone = true;

        controller.tick();
        assertEquals(CaptureState.IDLE, controller.state());
        assertEquals(1, session.finishes);

        controller.tick();
        assertEquals(CaptureState.IDLE, controller.state());
        assertEquals(1, session.finishes);
    }

    @Test
    void aSecondStartWhileSavingIsIgnored() {
        CaptureController controller = controller();
        FakeSession first = new FakeSession();
        controller.start(() -> first);
        controller.stop();

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
        controller.tick();

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

        controller.tick();

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
        session.capturedContainers = new CapturedContainers(new LongOpenHashSet(new long[] { 99L }), ImmutableSet.of(),
                false);
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
        session.recoveredCoverage = new RecoveredCoverage(new LongOpenHashSet(new long[] { 42L }));
        controller.start(() -> session);

        assertTrue(controller.recoveredCoverage().contains(42L));
    }

    @Test
    void recoveredCoverageIsEmptyWhenIdle() {
        assertSame(RecoveredCoverage.EMPTY, controller().recoveredCoverage());
    }

    private static WdlConfig entities(boolean on) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", Boolean.toString(on));
        properties.setProperty("renderCoverageOverlay", "true");
        return WdlConfig.parse(properties);
    }

    private static WdlConfig overlayOff() {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "true");
        properties.setProperty("renderCoverageOverlay", "false");
        return WdlConfig.parse(properties);
    }

    @Test
    void aidTogglesAreTheLiveSettingsWhileNothingIsRecording() {
        CaptureController controller = controller();

        assertTrue(controller.aidToggles(entities(true)).captureEntities(),
                "with no download running there is no latched set, so the settings are the whole answer");
        assertFalse(controller.aidToggles(entities(false)).captureEntities());
    }

    @Test
    void switchingAnAxisOnMidDownloadDoesNotDrawForIt() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.latchedToggles = CaptureToggles.from(entities(false));
        controller.start(() -> session);

        assertFalse(controller.aidToggles(entities(true)).captureEntities(),
                "the session latched the axis off, so no aid may claim it however the settings now read");
    }

    @Test
    void switchingAnAxisOffMidDownloadStopsDrawingForIt() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.latchedToggles = CaptureToggles.from(entities(true));
        controller.start(() -> session);

        assertFalse(controller.aidToggles(entities(false)).captureEntities(),
                "switching an axis off hides its markers at once, whatever the session latched");
    }

    @Test
    void aidTogglesReturnToTheLiveSettingsOnceTheSaveCompletes() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.latchedToggles = CaptureToggles.from(entities(false));
        controller.start(() -> session);
        controller.stop();
        session.saveDone = true;
        controller.tick();

        assertTrue(controller.aidToggles(entities(true)).captureEntities(),
                "the latched set does not outlive the download that latched it");
    }

    @Test
    void coveredOverlayStaysEmptyForAnAxisTheRunningDownloadLatchedOff() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.latchedToggles = CaptureToggles.from(entities(false));
        controller.start(() -> session);
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 0, 0, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        assertTrue(controller.coveredChunks().snapshot("minecraft:overworld").length > 0,
                "the covered index must be populated, or the assertion below would prove nothing");
        assertEquals(0, controller.overlayCoveredChunks(entities(true), "minecraft:overworld").length,
                "this download is adding no entity, so switching the settings on must not paint a covered tone");
    }

    @Test
    void savedOverlayIsEmptyWhileTheToggleIsOffAndTheSavedSetOnceOn() {
        CaptureController controller = controller();
        controller.savedChunks().add("minecraft:overworld", 42L);

        assertArrayEquals(new long[0], controller.overlaySavedChunks(overlayOff(), "minecraft:overworld"),
                "renderCoverageOverlay off hides the saved highlight");
        assertArrayEquals(new long[] { 42L }, controller.overlaySavedChunks(entities(true), "minecraft:overworld"),
                "on, the saved overlay reads back the recorded index");
    }

    @Test
    void coveredOverlayIsEmptyWhileTheToggleIsOff() {
        CaptureController controller = controller();
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 0, 0, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        assertArrayEquals(new long[0], controller.overlayCoveredChunks(overlayOff(), "minecraft:overworld"),
                "renderCoverageOverlay off hides the covered highlight even with a calibrated range mid-capture");
    }

    @Test
    void coveredOverlayMirrorsTheSavedSetUntilTheSendRangeCalibrates() {
        CaptureController controller = controller();
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 0, 0, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);

        assertFalse(controller.sendRange().isCalibrated("minecraft:overworld"),
                "no sample has been observed, so the range is not yet calibrated");
        assertTrue(controller.coveredChunks().snapshot("minecraft:overworld").length > 0,
                "the covered index must be populated, or the mirror assertion below proves nothing");
        assertArrayEquals(new long[] { 42L }, controller.overlayCoveredChunks(entities(true), "minecraft:overworld"),
                "before the range is known the covered read mirrors the saved set, not the covered index");
    }

    @Test
    void coveredOverlayDrawsTheCoveredSetOnceTheSendRangeCalibrates() {
        CaptureController controller = controller();
        controller.savedChunks().add("minecraft:overworld", 42L);
        controller.coveredChunks().recordTrail("minecraft:overworld", 0, 0, 8);
        controller.coveredChunks().recompute("minecraft:overworld", 1);
        controller.sendRange().observe("minecraft:overworld", 64);

        long[] covered = controller.coveredChunks().snapshot("minecraft:overworld");
        assertTrue(covered.length > 0, "the covered index must be populated for this branch to be observable");
        long[] painted = controller.overlayCoveredChunks(entities(true), "minecraft:overworld");
        Arrays.sort(covered);
        Arrays.sort(painted);
        assertArrayEquals(covered, painted, "once the range is calibrated the covered set draws, not the saved mirror");
    }

    @Test
    void aSavedChunkOrCoveredDiscMovesTheOverlayGeneration() {
        CaptureController controller = controller();
        long initial = controller.overlayGeneration();

        controller.savedChunks().add("minecraft:overworld", 42L);
        long afterSaved = controller.overlayGeneration();
        controller.coveredChunks().addDisc("minecraft:overworld", 0, 0, 1);

        assertTrue(afterSaved > initial, "a saved chunk moves the generation");
        assertTrue(controller.overlayGeneration() > afterSaved, "a covered disc moves the generation");
    }

    @Test
    void aSettingsCommitMovesTheOverlayGeneration() {
        CaptureController controller = controller();
        controller.start(FakeSession::new);
        controller.savedChunks().add("minecraft:overworld", 42L);
        long before = controller.overlayGeneration();

        controller.onSettingsCommitted();

        assertTrue(controller.overlayGeneration() > before,
                "an overlay toggle or color edit leaves both indexes untouched, so the commit itself must move it");
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

        controller.stop();
        session.counts = new CaptureCounts(5, 2, 9);

        assertEquals(5, controller.counts().chunks(), "frozen through saving");
        assertEquals(2, controller.counts().containers());
        assertEquals(3, controller.counts().entities());

        session.saveDone = true;
        controller.tick();

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
        controller.tick();

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
        controller.tick();

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
        controller.stop();

        now[0] = 30_000L;
        assertEquals(5_000L, controller.elapsedMillis(), "the timer holds the capture duration through saving");

        session.saveDone = true;
        controller.tick();
        now[0] = 31_000L;
        assertEquals(5_000L, controller.elapsedMillis(), "and through the done linger");

        now[0] = 200_000L;
        assertEquals(0L, controller.elapsedMillis(), "cleared once past the linger");
    }

    @Test
    void saveStageIsNoneAndProgressZeroUnlessSaving() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveStage = SaveStage.WRITING_MAPS;
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
        controller.stop();

        assertEquals(SaveStage.COMPRESSING, controller.saveStage());
        assertEquals(0.75f, controller.saveProgress());

        session.saveDone = true;
        controller.tick();
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
        controller.tick();

        now[0] = 12_500L;
        assertTrue(controller.doneElapsedMillis().isPresent(), "present once the save completes");
        assertEquals(2_500L, controller.doneElapsedMillis().getAsLong(), "elapsed since the save completed");

        now[0] = 200_000L;
        assertFalse(controller.doneElapsedMillis().isPresent(), "absent again once past the hold window");
    }

    @Test
    void restoringFlipIsAtomicFromIdleOnly() {
        CaptureController controller = controller();
        assertTrue(controller.tryBeginRestoring());
        assertEquals(CaptureState.RESTORING, controller.state());
        assertFalse(controller.tryBeginRestoring());
        controller.start(FakeSession::new);
        assertEquals(CaptureState.RESTORING, controller.state());
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

        controller.endRestoring();
        assertEquals(CaptureState.RECORDING, controller.state());
    }

    @Test
    void doneLingerHoldIsInclusiveAtItsExactBoundary() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        session.saveDone = true;
        controller.start(() -> session);
        controller.stop();
        controller.tick();

        now[0] = 60_000L; // exactly the 60s DONE_LINGER_HOLD_MILLIS
        assertTrue(controller.doneElapsedMillis().isPresent(), "the done frame still reads at the exact boundary");

        now[0] = 60_001L;
        assertFalse(controller.doneElapsedMillis().isPresent(), "and is gone one millisecond past it");
    }

    /**
     * On this band the loader's own disconnect callback arrives after the wiring has already flushed the download at
     * the level-teardown edge. A second disconnect must find the download already finished rather than starting a
     * second one.
     */
    @Test
    void aSecondDisconnectAfterTheFirstFinishedFinishesNothingFurther() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        controller.stop();
        controller.tick();
        controller.stop();

        assertEquals(1, session.finishes, "the finish the first disconnect ran is the only one");
    }

    @Test
    void disconnectHoldsTheWriterEvenWhenTheSaveIsAlreadyRunning() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.stop();
        assertEquals(CaptureState.SAVING, controller.state());
        assertEquals(0, session.holds, "stopping alone rebuilds nothing, so nothing is held");

        controller.onDisconnect();

        assertEquals(1, session.holds, "the disconnect holds the writer even though the flush it also runs is a no-op");
        assertEquals(0, session.releases, "and nothing releases it before the client has torn the level down");
    }

    @Test
    void theHoldOutlivesTheFinishAndIsReleasedByTheNextTick() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);

        controller.onDisconnect();

        assertEquals(1, session.holds, "the disconnect holds first");
        assertEquals(0, session.releases, "the finish it runs must not release its own hold");

        controller.tick();

        assertEquals(1, session.releases, "the first tick after the finish releases it");
    }

    /**
     * The join edge takes the hold too. On this band it arrives only once the loader has already rebuilt, so what this
     * pins is that the wiring reaches a session the state has left recording, not any coverage it buys here.
     */
    @Test
    void joiningHoldsTheWriterStillDrainingFromTheLastServer() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession();
        controller.start(() -> session);
        controller.stop();

        controller.onServerJoin();

        assertEquals(1, session.holds, "the join edge holds the still-draining writer");
    }

    @Test
    void aPollReenteringFromInsideTheFinishLeavesTheHoldForTheNextTick() {
        CaptureController controller = controller();
        FakeSession session = new FakeSession() {
            @Override
            public void finish() {
                super.finish();
                saveDone = true;
                controller.tick();
            }
        };
        controller.start(() -> session);

        controller.onDisconnect();

        assertEquals(CaptureState.IDLE, controller.state(), "the finish's own poll completed the save");
        assertEquals(1, session.holds, "the disconnect holds first");
        assertEquals(0, session.releases, "the finish's own poll must not release the hold");

        controller.tick();

        assertEquals(1, session.releases, "the first tick after the finish releases it");
    }
}
