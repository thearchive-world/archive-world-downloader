// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the fail-soft: the finish-time player assembly runs after chunks have committed to disk, so an
 * uncaught serialize or scrub throw there would abort before the writer finalizes and leave a chunks-without-level.dat
 * unopenable world plus a leaked lock. {@link LiveCaptureSession#failSoft} degrades any throw to a null snapshot, which
 * the (separately tested) null-{@code CapturedPlayer} level.dat path writes as the openable void world. The remaining
 * "the writer still finalizes" half is the last two tests here: the fail-soft above covers the throws it was written
 * for, and the guard those two pin covers the finish body wholesale, including the steps between the last submit and
 * the end-of-stream marker that no fail-soft wraps.
 *
 * <p>The degradation is deliberate, so the second half here is that it is not silent: a level.dat written with no
 * Player tag has lost the download's inventory, ender chest and game mode, and a verdict that still read clean over
 * that would be the completion record asserting something untrue. Both halves are asserted for each, since neither
 * alone is enough: the counter moved (the verdict sums every term, so a step charged to a sibling counter still reads
 * partial) and the verdict turned partial (a counter no term of the sum reads is a loss nothing reports). The
 * production call sites sit past the client reads, so what this pins is the contract they share and not that they still
 * call it.
 */
class LiveCaptureSessionFailSoftTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // initialize the vanilla static constants the value types touch
    }

    /**
     * A session with no bound level, which is all the fail-soft contract needs. Entity and container capture are off so
     * the constructor publishes no process-wide capture into the static activation slots, which only finish() would
     * clear; the toggles are asserted rather than assumed, since an unrecognized config key falls back to the default,
     * which is on for both.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        return session(new HeadlessPlatformBridge(configDirectory));
    }

    /** The headless bridge with its chat arm recording, for the one path whose subject is that it surfaces. */
    private static final class SurfacingBridge extends HeadlessPlatformBridge {
        private boolean surfaced;

        private SurfacingBridge(Path configDirectory) {
            super(configDirectory);
        }

        @Override
        public void sendChat(ChatCopy line) {
            surfaced = true;
        }

        @Override
        public void sendToast(ToastCopy toast) {}
    }

    private static LiveCaptureSession session(HeadlessPlatformBridge bridge) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), bridge,
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    @Test
    void failSoftDegradesThrowingAssemblyToNull(@TempDir Path temporary) {
        LiveCaptureSession session = session(temporary);

        assertNull(session.failSoft("player", () -> {
            throw new IllegalStateException("player serialize/scrub blew up");
        }), "a throwing assembly degrades to a null capturedPlayer so the openable void-world save still runs");
    }

    @Test
    void failSoftPassesThroughSuccessfulAssembly(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        CapturedPlayer captured = new CapturedPlayer(new CompoundTag(), BlockPos.ZERO, 0.0F, 0.0F,
                Level.OVERWORLD, GameType.CREATIVE, Difficulty.NORMAL);

        assertSame(captured, session.failSoft("player", () -> captured), "a successful assembly passes through");
        assertEquals(0, losses(session), "and a step that worked counts no loss");
        assertFalse(session.isPartialSave(0, 0), "so the finish reads clean");
    }

    @Test
    void aDegradedFinishStepLandsInTheFinishStepTallyAndTurnsTheVerdictPartial(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        assertFalse(session.isPartialSave(0, 0), "nothing has degraded, so the finish reads clean");

        session.failSoft("player", () -> {
            throw new IllegalStateException("player serialize/scrub blew up");
        });

        assertEquals(1, losses(session),
                "the level.dat is written with no Player tag, so the loss is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void eachDegradedStepIsCountedOnItsOwn(@TempDir Path temporary) throws Exception {
        // These are independent losses, and one of them (the salvaged mount) runs only because another
        // already failed, so a tally that latched on the first would under-report every finish that lost
        // more than one snapshot.
        LiveCaptureSession session = session(temporary);

        session.failSoft("player", () -> {
            throw new IllegalStateException("the player assembly blew up");
        });
        session.failSoft("progress", () -> {
            throw new IllegalStateException("the progress assembly blew up");
        });

        assertEquals(2, losses(session), "a lost player and a lost progress snapshot are two losses, not one");
    }

    /**
     * Bind a session to a writer with no storage behind it, which is all the end-of-stream contract needs: the guard
     * submits nothing, so the drain opens neither target, and what matters is whether the writer reached its finalize
     * at all. The finalizer arrives as a parameter so a test can read the session through it, on the writer thread, at
     * the instant the production one writes level.dat and stamps the completion record.
     */
    private static AsyncSaveWriter bindWriter(LiveCaptureSession session, Path temporary,
            AsyncSaveWriter.Finalizer finalizer) {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {});
        return session.bindWorldOpen(paths, archive, () -> new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                dimension -> {
                    throw new AssertionError("no entity was submitted, so the entities storage must not open");
                },
                () -> {},
                finalizer,
                () -> null,
                () -> {},
                new SaveProgress()));
    }

    /**
     * The finish body throwing anywhere on its way to the end-of-stream marker: the marker must still be enqueued, or
     * the writer thread parks in its queue take for the rest of the game session holding the world's session lock, with
     * no level.dat written, the future never completed, and nothing at all reported to the player. The verdict half
     * matters as much: a save that completes claiming a success it did not have is worse than the parked thread, so the
     * throw is counted, and it is counted before the marker so that the writer thread's own finalize reads it.
     */
    @Test
    void aThrowOnTheWayToTheMarkerStillFinalizesTheSaveAndCountsTheLoss(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        AtomicBoolean partialAtFinalize = new AtomicBoolean(false);
        AtomicBoolean finalized = new AtomicBoolean(false);
        AsyncSaveWriter writer = bindWriter(session, temporary, (chunksFailed, entityChunksFailed) -> {
            finalized.set(true);
            partialAtFinalize.set(session.isPartialSave(chunksFailed, entityChunksFailed));
        });
        assertEquals(0, losses(session), "nothing has degraded yet, so the count below reads a move and not a state");
        assertFalse(session.isPartialSave(0, 0), "and the finish reads clean");

        session.completeThroughWriter(Runnable::run, () -> {
            throw new IllegalStateException("a finish step between the last submit and the marker blew up");
        });
        AsyncSaveWriter.SaveResult result = writer.result().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the writer drained and finalized, so the folder is an openable save");
        assertTrue(finalized.get(),
                "a throw takes the finalizing end of stream, since the work got far enough to be interrupted");
        assertTrue(partialAtFinalize.get(),
                "and the finalize that stamps the completion record already saw the loss, so it cannot read clean");
        assertEquals(1, losses(session), "the throw skipped the rest of the finish, so it counts as one lost step");
        assertTrue(session.isPartialSave(0, 0), "which the main-thread verdict reads as partial too");
    }

    /**
     * The guard catches {@link Throwable} rather than {@link RuntimeException}, and the difference is the whole defect
     * for an Error: the terminal state matters more than which class of failure got in the way, so an Error must not be
     * the one throw that still parks the writer.
     */
    @Test
    void anErrorOnTheWayToTheMarkerIsCaughtLikeAnyOtherThrow(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        AsyncSaveWriter writer = bindWriter(session, temporary, (chunksFailed, entityChunksFailed) -> {});

        session.completeThroughWriter(Runnable::run, () -> {
            throw new StackOverflowError("a finish step recursed off the stack");
        });
        AsyncSaveWriter.SaveResult result = writer.result().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the writer still reached its terminal state");
        assertEquals(1, losses(session), "and the Error counts like any other degraded finish step");
    }

    /**
     * The same guard on the path that does not throw, which the test above cannot see, and it is not only the ordinary
     * finish that needs it: the nothing-captured exit returns without submitting anything, and a download whose every
     * chunk was dropped as void reaches it with a writer already open behind it.
     */
    @Test
    void aFinishThatDoesNotThrowReachesTheSameEndOfStreamAndCountsNothing(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean finalized = new AtomicBoolean(false);
        AsyncSaveWriter writer = bindWriter(session, temporary,
                (chunksFailed, entityChunksFailed) -> finalized.set(true));

        session.completeThroughWriter(Runnable::run, () -> ran.set(true));
        AsyncSaveWriter.SaveResult result = writer.result().get(30, TimeUnit.SECONDS);

        assertTrue(ran.get(), "the finish work runs, rather than being skipped by its own guard");
        assertFalse(result.failed(), "and the writer reaches the same terminal state");
        assertTrue(finalized.get(), "the folder gets its level.dat, which is what makes it openable at all");
        assertEquals(0, losses(session), "a finish that threw nothing counts no loss");
        assertFalse(session.isPartialSave(0, 0), "so the download reports clean");
    }

    /**
     * The one link no unit above pins: that {@link LiveCaptureSession#finish} routes through the guard at all.
     * Headless, {@code Minecraft.getInstance()} is null, so the finish body throws on its first client read, which is
     * the same shape as any other throw on the way to the marker and exercises the whole route. What this cannot pin is
     * the executor the production path passes.
     */
    @Test
    void finishItselfReachesTheEndOfStreamThroughTheGuard(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        AsyncSaveWriter writer = bindWriter(session, temporary, (chunksFailed, entityChunksFailed) -> {});

        session.finish();

        assertNotNull(writer.result().get(30, TimeUnit.SECONDS),
                "finish() signals end of stream through the guard, so the writer completes rather than parking");
        assertEquals(1, losses(session), "and the throw that got it there is counted like any other");
    }

    /**
     * A finish that never opened a world has no writer to signal, and so no future for the controller to poll either.
     * Nothing is at risk on disk there, since the world-open closes its own access when it fails before handing one
     * over, but a download that never completes leaves the controller saving for the rest of the session with no way to
     * start another, so this finish has to end and report itself.
     */
    @Test
    void aThrowWithNoWriterOpenIsReportedAndEndsTheDownload(@TempDir Path temporary) throws Exception {
        SurfacingBridge bridge = new SurfacingBridge(temporary);
        LiveCaptureSession session = session(bridge);

        session.completeThroughWriter(Runnable::run, () -> {
            throw new IllegalStateException("the finish blew up before a world was ever opened");
        });

        assertEquals(1, losses(session), "the throw still counts, so a later reader cannot read the finish clean");
        assertTrue(session.isPartialSave(0, 0), "and the verdict is partial");
        assertTrue(bridge.surfaced, "the player is told the download failed rather than left watching it save");
        assertTrue(session.isSaveComplete(), "and the controller can leave the saving state");
    }

    /**
     * The session's degraded-finish-step counter. Read reflectively because the verdict cannot tell one term of its sum
     * from another: a site rewired to a sibling counter still reads partial, so pinning the verdict alone would pass on
     * the mistake.
     */
    private static int losses(LiveCaptureSession session) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField("finishStepsFailed");
        counter.setAccessible(true);
        return counter.getInt(session);
    }
}
