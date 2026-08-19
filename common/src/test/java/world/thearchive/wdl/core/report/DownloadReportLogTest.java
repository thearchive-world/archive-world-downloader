// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.ReportFixtures;

class DownloadReportLogTest {
    private static final Instant FINISHED = Instant.parse("2026-06-22T07:14:05Z");

    private static DownloadIdentity identity(String id) {
        return ReportFixtures.identity(id, "survival.thearchive.world", "Updated to 26.1.2", "My World");
    }

    private static ReportEnvironment environment() {
        return ReportFixtures.environment("1.0.0-SNAPSHOT");
    }

    private static Map<String, String> settings() {
        return ReportFixtures.settings();
    }

    @Test
    void completedLineRoundTripsEveryField(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, new DownloadCounts(421, 367, 0), new SaveChunks(421, ImmutableList.of()), true));

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, null);
        assertEquals(1, downloads.size());
        DownloadSession session = downloads.get(0);
        assertTrue(session.isComplete());
        assertTrue(session.isClean());
        assertEquals(FINISHED, session.finishedAt());
        assertEquals("My World", session.identity().downloadName());
        assertEquals(421, session.counts().chunks());
        assertEquals(367, session.counts().entities());
        assertEquals(0, session.counts().containers());
        assertEquals("true", session.settings().get("debug.log_saved_chunks"));
        ReportEnvironment environment = session.environment();
        assertNotNull(environment);
        assertEquals("Paper", environment.serverBrand());
        assertEquals("overworld", environment.dimensionName());
        assertEquals("1.0.0-SNAPSHOT", environment.modVersion());
    }

    @Test
    void completedLineRoundTripsThePerDimensionBreakdown(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadCounts counts = new DownloadCounts(430, 12, 3,
                ImmutableList.of(new DimensionChunks("overworld", 400), new DimensionChunks("the_nether", 30)));
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, counts, new SaveChunks(430, ImmutableList.of()), true));

        DownloadCounts readCounts = DownloadReportLog.readDownloads(jsonl, null).get(0).counts();
        assertNotNull(readCounts);
        List<DimensionChunks> read = readCounts.dimensions();
        assertEquals(2, read.size());
        assertEquals("overworld", read.get(0).dimensionName());
        assertEquals(400, read.get(0).chunks());
        assertEquals("the_nether", read.get(1).dimensionName());
        assertEquals(30, read.get(1).chunks());
        assertEquals(readCounts.chunks(), read.stream().mapToInt(DimensionChunks::chunks).sum(),
                "the stored chunk total equals the sum of the per-dimension breakdown");
    }

    @Test
    void aPartialCompletionReadsBackNotClean(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, new DownloadCounts(10, 1, 0), new SaveChunks(10, ImmutableList.of()), false));

        DownloadSession session = DownloadReportLog.readDownloads(jsonl, null).get(0);
        assertTrue(session.isComplete());
        assertFalse(session.isClean(), "a completion written as not clean reads back partial");
    }

    @Test
    void anUnparseableFinishedAtReadsBackAsTheEpoch(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, new DownloadCounts(1, 1, 0), new SaveChunks(1, ImmutableList.of()), true));
        String corrupted = new String(Files.readAllBytes(jsonl), StandardCharsets.UTF_8).replace("2026-06-22T07:14:05Z",
                "not-a-time");
        Files.write(jsonl, corrupted.getBytes(StandardCharsets.UTF_8));

        DownloadSession session = DownloadReportLog.readDownloads(jsonl, null).get(0);
        assertTrue(session.isComplete(), "a bad timestamp does not demote the record to interrupted");
        assertEquals(Instant.EPOCH, session.finishedAt(),
                "an unparseable finish timestamp degrades to the epoch rather than tearing the record");
    }

    @Test
    void aPendingFileWithNoCompletedLineReadsAsOneInterruptedDownload(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        Path pending = directory.resolve("download.pending");
        Files.write(pending, (DownloadReportLog.pendingLine(identity("a"), environment(), settings())
                + "\n").getBytes(StandardCharsets.UTF_8));

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, pending);
        assertEquals(1, downloads.size());
        assertFalse(downloads.get(0).isComplete(), "a pending with no completed line is interrupted");
    }

    @Test
    void aStalePendingForAnAlreadyCompletedDownloadIsIgnored(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        Path pending = directory.resolve("download.pending");
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, new DownloadCounts(1, 1, 0), new SaveChunks(1, ImmutableList.of()), true));
        Files.write(pending, (DownloadReportLog.pendingLine(identity("a"), environment(), settings())
                + "\n").getBytes(StandardCharsets.UTF_8));

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, pending);
        assertEquals(1, downloads.size(), "the stale sentinel for a completed id is dropped");
        assertTrue(downloads.get(0).isComplete());
    }

    @Test
    void aTornTrailingLineIsSkipped(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, new DownloadCounts(1, 1, 0), new SaveChunks(1, ImmutableList.of()), true));
        Files.write(jsonl, "{\"v\":1,\"id\":\"b\"".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND); // a half-written crash tail, no newline

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, null);
        assertEquals(1, downloads.size(), "the torn trailing line is skipped, the good record stands");
    }

    @Test
    void aRecordWithoutDownloadNameReadsBackEmpty(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        Files.write(jsonl, ("{\"v\":1,\"id\":\"x\",\"startedAt\":\"2026-06-22T07:14:01Z\","
                + "\"finishedAt\":\"2026-06-22T07:14:05Z\",\"status\":\"complete\"}\n")
                        .getBytes(StandardCharsets.UTF_8));

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, null);
        assertEquals("", downloads.get(0).identity().downloadName(),
                "a record predating the field reads back an empty name, and the screen folder-derives");
    }

    @Test
    void completedLineRoundTripsTheSourceKind(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadIdentity replaySourced = new DownloadIdentity("k", Instant.parse("2026-07-19T10:00:00Z"),
                "Terbin", "uuid", "", "", "", "NeoForge", "21.11.42", "replay", "unidentified");

        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(replaySourced, environment(),
                settings(), FINISHED, new DownloadCounts(9, 9, 0), new SaveChunks(9, ImmutableList.of()), true));

        List<DownloadSession> downloads = DownloadReportLog.readDownloads(jsonl, null);
        assertEquals("unidentified", downloads.get(0).identity().sourceKind(),
                "the source kind survives the write-and-reread the report renderer depends on");
    }

    @Test
    void completedLineRoundTripsTheSaveChunkTotals(@TempDir Path directory) throws IOException {
        Path jsonl = directory.resolve("download.jsonl");
        DownloadCounts counts = new DownloadCounts(10, 5, 2,
                ImmutableList.of(new DimensionChunks("minecraft:overworld", 10)));
        SaveChunks saveChunks = new SaveChunks(1000,
                ImmutableList.of(new DimensionChunks("minecraft:overworld", 800),
                        new DimensionChunks("minecraft:the_nether", 200)));
        DownloadReportLog.append(jsonl, DownloadReportLog.completedLine(identity("a"), environment(),
                settings(), FINISHED, counts, saveChunks, true));

        DownloadSession session = DownloadReportLog.readDownloads(jsonl, null).get(0);
        SaveChunks read = session.saveChunks();
        assertNotNull(read);
        assertEquals(1000, read.total());
        assertEquals(2, read.dimensions().size());
        assertEquals("minecraft:the_nether", read.dimensions().get(1).dimensionName());
        assertEquals(200, read.dimensions().get(1).chunks());
        // One session dimension proves the sd. entries did not leak into the d. parse, and the settings
        // round-trip proves they did not leak into the s. parse either.
        assertEquals(1, session.counts().dimensions().size());
        assertEquals(settings(), session.settings());
    }
}
