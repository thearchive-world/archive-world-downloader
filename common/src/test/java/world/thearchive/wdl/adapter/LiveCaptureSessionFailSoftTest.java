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
import net.minecraft.world.level.dimension.DimensionType;
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
 * Neither half alone is enough: the counter moved (the verdict sums every term, so a step charged to a sibling counter
 * still reads partial) and the verdict turned partial (a counter no term of the sum reads is a loss the verdict does
 * not report).
 */
class LiveCaptureSessionFailSoftTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots, which only the finish path clears; the toggles are asserted rather than assumed, since an
     * unrecognized config key falls back to the default, which is on for both.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        return session(new HeadlessPlatformBridge(configDirectory));
    }

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
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
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
                DimensionType.OVERWORLD, GameType.CREATIVE, Difficulty.NORMAL);

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

        assertEquals(1, losses(session), "the level.dat is written with no Player tag, so the loss is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void eachDegradedStepIsCountedOnItsOwn(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);

        session.failSoft("player", () -> {
            throw new IllegalStateException("the player assembly blew up");
        });
        session.failSoft("progress", () -> {
            throw new IllegalStateException("the progress assembly blew up");
        });

        assertEquals(2, losses(session), "a lost player and a lost progress snapshot are two losses, not one");
    }

    private static AsyncSaveWriter bindWriter(LiveCaptureSession session, Path temporary,
            AsyncSaveWriter.Finalizer finalizer) {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        MapArchive archive = new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {});
        return session.bindWorldOpen(paths, archive, () -> new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                () -> {},
                () -> {},
                finalizer,
                () -> null,
                new SaveProgress()));
    }

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

    @Test
    void holdAndReleaseReachTheWriterTheSessionOwns(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        AsyncSaveWriter writer = bindWriter(session, temporary, (chunksFailed, entityChunksFailed) -> {});

        session.holdWriterEncoding();
        assertTrue(writer.isEncodingPaused(), "the hold reaches the writer");

        session.releaseWriterEncoding();
        assertFalse(writer.isEncodingPaused(), "and the release lets it go");

        session.finish();
        assertNotNull(writer.result().get(30, TimeUnit.SECONDS), "the writer still reaches its terminal state");
    }

    /**
     * Headless, {@code Minecraft.getInstance()} is null, so the finish body throws on its first client read.
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

    private static int losses(LiveCaptureSession session) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField("finishStepsFailed");
        counter.setAccessible(true);
        return counter.getInt(session);
    }
}
