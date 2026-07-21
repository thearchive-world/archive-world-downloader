// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.ReportFixtures;

class DownloadReportStoreTest {
    private static final Instant FINISHED = Instant.parse("2026-06-22T07:14:05Z");

    private final List<LogRecord> warnings = new ArrayList<>();
    private Logger logger;
    private Handler handler;
    private boolean usedParentHandlers;

    @BeforeEach
    void attachLogCapture() {
        logger = Logger.getLogger(DownloadReportStore.class.getName());
        usedParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void detachLogCapture() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(usedParentHandlers);
    }

    private static DownloadIdentity identity() {
        return ReportFixtures.identity("id-1", "survival.thearchive.world", "hi", "My World");
    }

    private static ReportEnvironment environment() {
        return ReportFixtures.environment("1.0.0-SNAPSHOT");
    }

    private static Map<String, String> settings() {
        return ReportFixtures.settings();
    }

    private static Path wdl(Path directory, String name) {
        return directory.resolve("wdl").resolve(name);
    }

    @Test
    void beginWritesOnlyThePendingSentinel(@TempDir Path directory) {
        new DownloadReportStore().begin(directory, identity(), environment(), settings());

        assertTrue(Files.exists(wdl(directory, "download.pending")),
                "the pending sentinel marks an unfinished download");
        assertFalse(Files.exists(wdl(directory, "download.md")),
                "the rendering waits for the writer preflight, so the pre-resume backup archives the prior "
                        + "session's download.md untouched");
        assertFalse(Files.exists(wdl(directory, "download.jsonl")), "no completed line until finish");
    }

    @Test
    void refreshHumanRenderingWritesTheInProgressMarkdown(@TempDir Path directory) throws IOException {
        DownloadReportStore store = new DownloadReportStore();
        store.begin(directory, identity(), environment(), settings());

        store.refreshHumanRendering(directory);

        assertTrue(Files.exists(wdl(directory, "download.md")), "the in-progress rendering appears on refresh");
        List<DownloadSession> downloads = DownloadReportLog.readDownloads(wdl(directory, "download.jsonl"),
                wdl(directory, "download.pending"));
        assertEquals(1, downloads.size());
        assertFalse(downloads.get(0).isComplete(), "the refreshed rendering reflects the pending download");
    }

    @Test
    void refreshHumanRenderingIsFailSoft(@TempDir Path directory) throws IOException {
        Path blocker = directory.resolve("blocker");
        Files.write(blocker, new byte[] { 1 });

        assertDoesNotThrow(() -> new DownloadReportStore().refreshHumanRendering(blocker));
        assertFalse(warnings.isEmpty(), "a failed rendering write is caught and surfaced, never thrown");
    }

    @Test
    void completeAppendsTheLineAndDeletesTheSentinel(@TempDir Path directory) throws IOException {
        DownloadReportStore store = new DownloadReportStore();
        store.begin(directory, identity(), environment(), settings());
        store.complete(directory, identity(), environment(), settings(), FINISHED, new DownloadCounts(421, 367, 0),
                () -> new SaveChunks(0, Collections.<DimensionChunks>emptyList()), true);

        assertTrue(Files.exists(wdl(directory, "download.jsonl")));
        assertFalse(Files.exists(wdl(directory, "download.pending")), "the sentinel is deleted on a clean finish");
        List<DownloadSession> downloads = DownloadReportLog.readDownloads(wdl(directory, "download.jsonl"),
                wdl(directory, "download.pending"));
        assertEquals(1, downloads.size());
        assertTrue(downloads.get(0).isComplete());
    }

    @Test
    void completionIsAtMostOnceAcrossTwoCallSites(@TempDir Path directory) throws IOException {
        DownloadReportStore store = new DownloadReportStore();
        store.begin(directory, identity(), environment(), settings());
        store.complete(directory, identity(), environment(), settings(), FINISHED, new DownloadCounts(1, 1, 0),
                () -> new SaveChunks(0, Collections.<DimensionChunks>emptyList()), true);
        store.complete(directory, identity(), environment(), settings(), FINISHED, new DownloadCounts(1, 1, 0),
                () -> new SaveChunks(0, Collections.<DimensionChunks>emptyList()), true);

        assertEquals(1, Files.readAllLines(wdl(directory, "download.jsonl")).size(), "exactly one completed line");
    }

    @Test
    void aBegunButUncompletedDownloadReadsAsInterrupted(@TempDir Path directory) throws IOException {
        DownloadReportStore store = new DownloadReportStore();
        store.begin(directory, identity(), environment(), settings());

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(wdl(directory, "download.jsonl"),
                wdl(directory, "download.pending"));
        assertEquals(1, downloads.size());
        assertFalse(downloads.get(0).isComplete());
    }

    @Test
    void writesAreFailSoftWhenTheSaveRootCannotBeWritten(@TempDir Path directory) throws IOException {
        Path blocker = directory.resolve("blocker");
        Files.write(blocker, new byte[] { 1 });
        DownloadReportStore store = new DownloadReportStore();

        assertDoesNotThrow(() -> store.begin(blocker, identity(), environment(), settings()));
        assertDoesNotThrow(() -> store.complete(blocker, identity(), environment(), settings(), FINISHED,
                new DownloadCounts(1, 1, 0), () -> new SaveChunks(0, Collections.<DimensionChunks>emptyList()),
                true));
        assertFalse(warnings.isEmpty(), "a failed report write is caught and surfaced, never thrown");
    }

    @Test
    void aSecondCompletionNeverRunsTheScanAgain(@TempDir Path saveRoot) {
        DownloadReportStore store = new DownloadReportStore();
        AtomicInteger scans = new AtomicInteger();
        Supplier<SaveChunks> scan = () -> {
            scans.incrementAndGet();
            return new SaveChunks(1, Collections.<DimensionChunks>emptyList());
        };
        store.complete(saveRoot, identity(), environment(), settings(), FINISHED,
                new DownloadCounts(1, 0, 0), scan, true);
        store.complete(saveRoot, identity(), environment(), settings(), FINISHED,
                new DownloadCounts(1, 0, 0), scan, true);
        assertEquals(1, scans.get(), "the latch must swallow the second scan, not only the second line");
    }
}
