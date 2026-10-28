// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.report.DownloadIdentity;
import world.thearchive.wdl.core.report.DownloadReportLog;
import world.thearchive.wdl.core.report.DownloadSession;
import world.thearchive.wdl.core.report.ReportEnvironment;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Both arms are asserted, since either alone passes on a constant.
 */
class LiveCaptureSessionCompletionRecordTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots. All four toggles are asserted rather than assumed: an unrecognized config key falls back to the
     * default, which is on for all four.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("showChatMessages", "false");
        properties.setProperty("showToasts", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        assertFalse(config.showChatMessages(), "the chat arm reaches for a client this session does without");
        assertFalse(config.showToasts(), "the toast arm reaches for a player this session does without");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static AsyncSaveWriter.SaveResult cleanWrite() {
        return new AsyncSaveWriter.SaveResult(0, 0, 0, 0, 0, 0, 0, 0, null, null);
    }

    @Test
    void aDownloadThatLostNothingRecordsAsComplete(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path saveRoot = temporary.resolve("save");
        beginReport(session, saveRoot);
        assertFalse(session.isPartialSave(0, 0), "nothing was lost, so the predicate the record reads says clean");

        freezeAndReport(session, cleanWrite());

        assertTrue(recordedSession(saveRoot).isClean(),
                "a download that lost nothing stamps its completion record clean, which is what the downloads "
                        + "screen shows the user");
    }

    @Test
    void aDownloadThatLostSomethingRecordsAsPartial(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path saveRoot = temporary.resolve("save");
        beginReport(session, saveRoot);
        setCount(session, "chunksCaptureFailed", 1);
        assertTrue(session.isPartialSave(0, 0), "one lost chunk is enough for the predicate to say partial");

        freezeAndReport(session, cleanWrite());

        assertFalse(recordedSession(saveRoot).isClean(),
                "and the record stamps that verdict rather than the clean one, so the loss is not erased between "
                        + "the tally and the durable artifact");
    }

    @Test
    void aFinishThatFoundNoLivePlayerRecordsAsPartial(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path saveRoot = temporary.resolve("save");
        beginReport(session, saveRoot);
        assertFalse(session.isPartialSave(0, 0), "the predicate reads clean until the missing record is counted");

        session.countMissingPlayerRecord();

        assertTrue(session.isPartialSave(0, 0),
                "a save carrying no player record is partial: it opens at the default spawn rather than where the "
                        + "download was taken, and carries none of the inventory, ender chest, advancements, "
                        + "statistics or game mode");

        freezeAndReport(session, cleanWrite());

        DownloadSession recorded = recordedSession(saveRoot);
        assertFalse(recorded.isClean(),
                "and the record carries that verdict, which is what shows the user a resumable partial download "
                        + "instead of telling them a save missing its player is finished");
        assertEquals(Collections.singletonMap("player_records", 1), recorded.losses(),
                "naming the axis it lost on, and only that one, so the report tells the user what to go back for "
                        + "rather than only that something went wrong");
    }

    @Test
    void aCleanDownloadRecordsNoLossAtAll(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path saveRoot = temporary.resolve("save");
        beginReport(session, saveRoot);

        freezeAndReport(session, cleanWrite());

        assertEquals(Collections.<String, Integer>emptyMap(), recordedSession(saveRoot).losses(),
                "a download that lost nothing names no axis: the breakdown is the losses that moved, so a reader "
                        + "seeing an axis knows it is real rather than a zero row");
    }

    private static DownloadSession recordedSession(Path saveRoot) throws Exception {
        List<DownloadSession> downloads = DownloadReportLog.readDownloads(saveRoot);
        assertEquals(1, downloads.size(), "the finish writes exactly one completion record");
        DownloadSession recorded = downloads.get(0);
        assertTrue(recorded.isComplete(), "the record is the completed one, whose clean flag is the subject here");
        return recorded;
    }

    private static void beginReport(LiveCaptureSession session, Path saveRoot) throws Exception {
        set(session, "reportRoot", saveRoot);
        set(session, "reportIdentity", new DownloadIdentity("headless-download", Instant.EPOCH, "downloader",
                "00000000-0000-0000-0000-000000000000", "", "", "", "loader", "1", "headless", "unidentified"));
        set(session, "reportEnvironment", new ReportEnvironment("", 0, "minecraft:overworld", "1.21.11", "0"));
    }

    private static void freezeAndReport(LiveCaptureSession session, AsyncSaveWriter.SaveResult result)
            throws Exception {
        invoke(session, "prepareReportCompletion");
        Method report = LiveCaptureSession.class.getDeclaredMethod("report", AsyncSaveWriter.SaveResult.class);
        report.setAccessible(true);
        report.invoke(session, result);
    }

    private static void invoke(LiveCaptureSession session, String name) throws Exception {
        Method method = LiveCaptureSession.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(session);
    }

    private static void set(LiveCaptureSession session, String name, Object value) throws Exception {
        field(name).set(session, value);
    }

    private static void setCount(LiveCaptureSession session, String name, int value) throws Exception {
        field(name).setInt(session, value);
    }

    private static Field field(String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
