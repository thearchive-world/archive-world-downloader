// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.PngFixtures.png;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.report.DimensionChunks;
import world.thearchive.wdl.core.report.DownloadCounts;
import world.thearchive.wdl.core.report.DownloadIdentity;
import world.thearchive.wdl.core.report.DownloadReportLog;
import world.thearchive.wdl.core.report.DownloadReportStore;
import world.thearchive.wdl.core.report.ReportEnvironment;
import world.thearchive.wdl.core.report.SaveChunks;
import world.thearchive.wdl.core.report.WorldIconWriter;
import world.thearchive.wdl.testsupport.ReportFixtures;

class DownloadCatalogTest {
    private static final Instant STARTED = Instant.parse("2026-06-22T07:14:01Z");

    @Test
    void excludesPlainVanillaSavesAndListsOnlyWdlManagedFolders(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("vanilla-save")); // no wdl/ subfolder
        writeComplete(saves.resolve("download-2026-06-22"), "a", "Download", STARTED.plusSeconds(4),
                new DownloadCounts(10, 5, 0));

        List<DownloadEntry> entries = DownloadCatalog.list(saves, null);

        assertEquals(1, entries.size());
        assertEquals("download-2026-06-22", entries.get(0).folderName());
    }

    @Test
    void sortsMostRecentlyPlayedFirst(@TempDir Path saves) throws IOException {
        writeComplete(saves.resolve("older-2026-06-20"), "a", "Older", Instant.parse("2026-06-20T10:00:00Z"),
                new DownloadCounts(1, 1, 0));
        writeComplete(saves.resolve("newer-2026-06-22"), "b", "Newer", Instant.parse("2026-06-22T10:00:00Z"),
                new DownloadCounts(1, 1, 0));

        List<DownloadEntry> entries = DownloadCatalog.list(saves, null);

        assertEquals("newer-2026-06-22", entries.get(0).folderName());
        assertEquals("older-2026-06-20", entries.get(1).folderName());
    }

    @Test
    void aCompleteDownloadExposesSummaryAndIcon(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("mybase-2026-06-22");
        writeComplete(folder, "a", "My Base", STARTED.plusSeconds(4), new DownloadCounts(456, 259, 0));
        WorldIconWriter.write(folder, png(64, 64));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals(DownloadHealth.COMPLETE, entry.health());
        assertEquals("My Base", entry.displayName());
        assertEquals("My Base", entry.worldName());
        assertNotNull(entry.counts());
        assertEquals(456, entry.counts().chunks());
        assertNotNull(entry.iconBytes(), "a valid icon is read and validated");
    }

    @Test
    void aPartialDownloadIsMarkedPartialButKeepsItsSummary(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("lossy-2026-06-22");
        writePartial(folder, "a", "Lossy", STARTED.plusSeconds(4), new DownloadCounts(400, 10, 0));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals(DownloadHealth.PARTIAL, entry.health(), "a not-clean completion reads as partial, not complete");
        assertNotNull(entry.counts(), "a partial download keeps its summary");
        assertEquals(400, entry.counts().chunks());
    }

    @Test
    void aRecoverableDownloadExposesNoSummary(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("crashed-2026-06-22");
        writePending(folder, "a", "Crashed");

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals(DownloadHealth.RECOVERABLE, entry.health());
        assertNull(entry.counts(), "a recoverable download has no trustworthy summary");
    }

    @Test
    void theDisplayNameFallsBackToTheFolderWhenTheRecordHasNoName(@TempDir Path saves) throws IOException {
        writeComplete(saves.resolve("base-2026-06-22"), "a", "", STARTED.plusSeconds(4),
                new DownloadCounts(1, 1, 0));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals("base-2026-06-22", entry.worldName(), "the field/level.dat name keeps the date");
        assertEquals("base", entry.displayName(), "the row label strips the trailing date");
    }

    @Test
    void theCurrentlyLoadedWorldIsFlagged(@TempDir Path saves) throws IOException {
        Path loaded = saves.resolve("loaded-2026-06-22");
        writeComplete(loaded, "a", "Loaded", STARTED.plusSeconds(4), new DownloadCounts(1, 1, 0));
        writeComplete(saves.resolve("other-2026-06-22"), "b", "Other", STARTED.plusSeconds(2),
                new DownloadCounts(1, 1, 0));

        List<DownloadEntry> entries = DownloadCatalog.list(saves, loaded);

        assertTrue(entryFor(entries, "loaded-2026-06-22").isCurrentlyLoaded());
        assertFalse(entryFor(entries, "other-2026-06-22").isCurrentlyLoaded());
    }

    @Test
    void anUnreadableFolderIsSkippedAndDoesNotHideTheOthers(@TempDir Path saves) throws IOException {
        writeComplete(saves.resolve("healthy-2026-06-22"), "a", "Healthy", STARTED.plusSeconds(4),
                new DownloadCounts(1, 1, 0));
        // A poison folder: download.jsonl exists but is a directory, so reading it throws.
        Files.createDirectories(saves.resolve("poison-2026-06-22").resolve("wdl").resolve("download.jsonl"));

        List<DownloadEntry> entries = DownloadCatalog.list(saves, null);

        assertEquals(1, entries.size(), "a folder that fails to read is skipped, not fatal to the whole list");
        assertEquals("healthy-2026-06-22", entries.get(0).folderName());
    }

    @Test
    void aCompletedLineWithLiveSentinelReadsRecoverable(@TempDir Path saves) throws IOException {
        // A clean finish, then a resume that crashed: a completed line for id a plus a live sentinel for id b.
        Path folder = saves.resolve("resumed-2026-06-22");
        writeComplete(folder, "a", "Resumed", STARTED.plusSeconds(4), new DownloadCounts(50, 50, 0));
        writePending(folder, "b", "Resumed");

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals(DownloadHealth.RECOVERABLE, entry.health(), "a surviving sentinel wins over a completed line");
        assertNull(entry.counts());
    }

    @Test
    void aFolderMarkedWithNoParseableSessionReadsRecoverable(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("marked-2026-06-22");
        Path pending = DownloadReportStore.pendingFile(folder);
        Files.createDirectories(pending.getParent());
        Files.write(pending, new byte[0]); // a torn/empty sentinel: marked, but no parseable session

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals(DownloadHealth.RECOVERABLE, entry.health());
        assertEquals("marked", entry.displayName(), "the folder name (date stripped) labels a record-less row");
    }

    @Test
    void theLatestCompletedLineByFinishTimeDrivesTheRow(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("extended-2026-06-22");
        writeComplete(folder, "a", "Old", Instant.parse("2026-06-22T08:00:00Z"), new DownloadCounts(10, 10, 0));
        writeComplete(folder, "b", "New", Instant.parse("2026-06-22T09:00:00Z"), new DownloadCounts(99, 99, 0));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertEquals("New", entry.displayName());
        assertNotNull(entry.counts());
        assertEquals(99, entry.counts().chunks(), "the latest completed line by finish time wins");
    }

    @Test
    void theLatestCompletedLineWinsRegardlessOfOnDiskOrder(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("reordered-2026-06-22");
        // Newer line written FIRST on disk, older second: selection is by finish time, not append order.
        writeComplete(folder, "b", "New", Instant.parse("2026-06-22T09:00:00Z"), new DownloadCounts(99, 99, 0));
        writeComplete(folder, "a", "Old", Instant.parse("2026-06-22T08:00:00Z"), new DownloadCounts(10, 10, 0));

        assertEquals("New", DownloadCatalog.list(saves, null).get(0).displayName());
    }

    @Test
    void anOversizedIconOnDiskIsSkippedWithoutReadingIt(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("bigicon-2026-06-22");
        writeComplete(folder, "a", "BigIcon", STARTED.plusSeconds(4), new DownloadCounts(1, 1, 0));
        WorldIconWriter.write(folder, new byte[WorldIcon.MAX_BYTES + 1]); // larger than the byte cap

        assertNull(DownloadCatalog.list(saves, null).get(0).iconBytes(), "an oversized icon is skipped");
    }

    @Test
    void marksFolderOpenedInSingleplayerAsTainted(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("touched-2026-06-22");
        writeComplete(folder, "a", "Touched", STARTED.plusSeconds(4), new DownloadCounts(10, 5, 0));
        Files.createDirectories(folder.resolve("playerdata"));
        Files.createFile(folder.resolve("playerdata").resolve("host.dat"));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertTrue(entry.isTainted());
    }

    @Test
    void leavesAnUntouchedFolderNotTainted(@TempDir Path saves) throws IOException {
        Path folder = saves.resolve("clean-2026-06-22");
        writeComplete(folder, "a", "Clean", STARTED.plusSeconds(4), new DownloadCounts(10, 5, 0));

        DownloadEntry entry = DownloadCatalog.list(saves, null).get(0);

        assertFalse(entry.isTainted());
    }

    @Test
    void resumedRowShowsTheInSaveChunkTotalOnly(@TempDir Path savesDirectory) throws IOException {
        Path folder = savesDirectory.resolve("resumed-world");
        DownloadReportLog.append(DownloadReportStore.machineFile(folder),
                DownloadReportLog.completedLine(ReportFixtures.identity("a", "survival.thearchive.world",
                        "", "Resumed World"), ReportFixtures.environment("1.0.0-SNAPSHOT"),
                        ReportFixtures.settings(), Instant.parse("2026-07-24T00:00:00Z"),
                        new DownloadCounts(10, 5, 2),
                        new SaveChunks(1000, Collections.<DimensionChunks>emptyList()), false));

        List<DownloadEntry> entries = DownloadCatalog.list(savesDirectory, null);

        assertEquals(1, entries.size());
        DownloadEntry entry = entries.get(0);
        assertEquals(DownloadHealth.PARTIAL, entry.health(), "a partial record composes the same way");
        assertTrue(entry.isChunksOnly(), "a resumed save marks the row chunks-only");
        DownloadCounts rowCounts = entry.counts();
        assertNotNull(rowCounts);
        assertEquals(1000, rowCounts.chunks(), "the cumulative in-save chunk total");
    }

    @Test
    void notExceedingSaveTotalFallsBackToTheSessionCounts(@TempDir Path savesDirectory) throws IOException {
        // Covers both fallback shapes: a zero total (whole scan failed) and an undercounted total below the
        // session's own chunks (partial scan); neither may put a smaller-than-session chunk number on screen,
        // and neither is chunks-only (all three session numbers stay on the row).
        Path zeroFolder = savesDirectory.resolve("scanless-world");
        DownloadReportLog.append(DownloadReportStore.machineFile(zeroFolder),
                DownloadReportLog.completedLine(ReportFixtures.identity("a", "survival.thearchive.world",
                        "", "Scanless World"), ReportFixtures.environment("1.0.0-SNAPSHOT"),
                        ReportFixtures.settings(), Instant.parse("2026-07-24T00:00:00Z"),
                        new DownloadCounts(10, 5, 2),
                        new SaveChunks(0, Collections.<DimensionChunks>emptyList()), true));
        Path undercountFolder = savesDirectory.resolve("undercount-world");
        DownloadReportLog.append(DownloadReportStore.machineFile(undercountFolder),
                DownloadReportLog.completedLine(ReportFixtures.identity("b", "survival.thearchive.world",
                        "", "Undercount World"), ReportFixtures.environment("1.0.0-SNAPSHOT"),
                        ReportFixtures.settings(), Instant.parse("2026-07-24T00:00:00Z"),
                        new DownloadCounts(10, 5, 2),
                        new SaveChunks(4, Collections.<DimensionChunks>emptyList()), true));

        List<DownloadEntry> entries = DownloadCatalog.list(savesDirectory, null);

        assertEquals(2, entries.size(), "both fallback rows reach the catalog at all");
        for (DownloadEntry entry : entries) {
            assertFalse(entry.isChunksOnly(), "a non-exceeding total is not chunks-only");
            DownloadCounts rowCounts = entry.counts();
            assertNotNull(rowCounts);
            assertEquals(10, rowCounts.chunks(), "no smaller-than-session chunk number on screen");
        }
    }

    @Test
    void anEqualSaveTotalIsNotChunksOnly(@TempDir Path savesDirectory) throws IOException {
        // The common clean single-session case: the scan total equals the session's own chunks, adding
        // nothing, so the row keeps all three session numbers and is not chunks-only. This pins the gate at
        // exceeding, not at differing and not at not-below: DownloadCatalog is not mutation-enrolled, so
        // without it a > to >= regression would silently dash a plain single-session row.
        Path folder = savesDirectory.resolve("equal-world");
        DownloadReportLog.append(DownloadReportStore.machineFile(folder),
                DownloadReportLog.completedLine(ReportFixtures.identity("a", "survival.thearchive.world",
                        "", "Equal World"), ReportFixtures.environment("1.0.0-SNAPSHOT"),
                        ReportFixtures.settings(), Instant.parse("2026-07-24T00:00:00Z"),
                        new DownloadCounts(10, 5, 2),
                        new SaveChunks(10, Collections.<DimensionChunks>emptyList()), true));

        List<DownloadEntry> entries = DownloadCatalog.list(savesDirectory, null);

        assertEquals(1, entries.size());
        DownloadEntry entry = entries.get(0);
        assertFalse(entry.isChunksOnly(), "an equal save total is not chunks-only");
        DownloadCounts rowCounts = entry.counts();
        assertNotNull(rowCounts);
        assertEquals(10, rowCounts.chunks());
        assertEquals(5, rowCounts.entities(), "an equal total keeps the session entity count on the row");
        assertEquals(2, rowCounts.containers(), "and the session container count");
    }

    private static DownloadEntry entryFor(List<DownloadEntry> entries, String folderName) {
        return entries.stream().filter(entry -> entry.folderName().equals(folderName)).findFirst().orElseThrow();
    }

    private static DownloadIdentity identity(String id, String name) {
        return ReportFixtures.identity(id, "addr", "motd", name);
    }

    private static ReportEnvironment environment() {
        return ReportFixtures.environment("1.0.0");
    }

    private static void writeComplete(Path folder, String id, String name, Instant finished,
            DownloadCounts counts) throws IOException {
        DownloadReportLog.append(DownloadReportStore.machineFile(folder), DownloadReportLog.completedLine(
                identity(id, name), environment(), Collections.<String, String>emptyMap(), finished, counts,
                new SaveChunks(0, List.of()), true));
    }

    private static void writePartial(Path folder, String id, String name, Instant finished,
            DownloadCounts counts) throws IOException {
        DownloadReportLog.append(DownloadReportStore.machineFile(folder), DownloadReportLog.completedLine(
                identity(id, name), environment(), Collections.<String, String>emptyMap(), finished, counts,
                new SaveChunks(0, List.of()), false));
    }

    private static void writePending(Path folder, String id, String name) throws IOException {
        Path pending = DownloadReportStore.pendingFile(folder);
        Files.createDirectories(pending.getParent());
        Files.write(pending, (DownloadReportLog.pendingLine(identity(id, name), environment(),
                Collections.<String, String>emptyMap()) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
