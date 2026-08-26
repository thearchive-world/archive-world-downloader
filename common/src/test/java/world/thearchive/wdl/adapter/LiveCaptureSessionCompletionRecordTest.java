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
import java.util.List;
import java.util.Properties;
import net.minecraft.world.DimensionType;
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
 * The clean-or-partial flag as it reaches disk. Every loss tally in the tree is pinned against the predicate, and the
 * record's own reader is pinned against a flag handed to it, but between the two sits the derivation that turns one
 * into the other, which nothing else asserts at any tier. It is one line, and one line is enough: a dropped negation
 * stamps every download that lost something as complete, which is exactly the outcome every one of those tallies exists
 * to prevent, and the downloads screen reads that status.
 *
 * <p>Both arms are asserted, since either alone passes on a constant: a clean save must record complete and a save
 * carrying one counted loss must record partial. The loss is seeded on a single tally rather than driven from a
 * capture, because which tally moved is the sibling tests' subject and the derivation reads only their sum.
 *
 * <p>The completion inputs are seeded reflectively. Production stamps them at world-open through {@code beginReport},
 * which takes the client singleton no headless session can answer, and the two steps that consume them are private; the
 * state seeded here is the record's own identity, not capture state, so nothing about the verdict under test is stood
 * in for. What that leaves unpinned is the production ordering, that world-open stamps the triple and finish freezes
 * the counts before the writer finalizes; the record is only written at all when both have run.
 */
class LiveCaptureSessionCompletionRecordTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap(); // vanilla statics, which building a session needs before it runs
    }

    /**
     * A session with no bound level, reporting to nobody. Entity and container capture are off so the constructor
     * publishes no process-wide capture into the static activation slots; chat and toasts are off because the headless
     * bridge throws on both and the chat arm resolves the save folder through the client. All four are asserted rather
     * than assumed: an unrecognized config key falls back to the default, which is on for all four.
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
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    /** A save that wrote everything it was given, so the whole verdict is the session's own tallies. */
    private static AsyncSaveWriter.SaveResult cleanWrite() {
        return new AsyncSaveWriter.SaveResult(0, 0, 0, 0, 0, 0, 0, null, null);
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

    /**
     * The one completion record written under {@code saveRoot}. Read through the production reader, so a flag written
     * under the wrong key or in the wrong sense fails here rather than passing a raw-text match.
     */
    private static DownloadSession recordedSession(Path saveRoot) throws Exception {
        List<DownloadSession> downloads = DownloadReportLog.readDownloads(saveRoot);
        assertEquals(1, downloads.size(), "the finish writes exactly one completion record");
        DownloadSession recorded = downloads.get(0);
        assertTrue(recorded.isComplete(), "the record is the completed one, whose clean flag is the subject here");
        return recorded;
    }

    /**
     * Stamp the report identity, which production does at world-open through the client singleton. The completion write
     * returns early without it, so this is what opens the path rather than anything about the verdict.
     */
    private static void beginReport(LiveCaptureSession session, Path saveRoot) throws Exception {
        set(session, "reportRoot", saveRoot);
        set(session, "reportIdentity", new DownloadIdentity("headless-download", Instant.EPOCH, "downloader",
                "00000000-0000-0000-0000-000000000000", "", "", "", "loader", "1", "headless", "unidentified"));
        set(session, "reportEnvironment", new ReportEnvironment("", 0, "minecraft:overworld", "1.21.11", "0"));
    }

    /** Freeze the end-of-capture counts, then run the finish report that writes the completion record. */
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
