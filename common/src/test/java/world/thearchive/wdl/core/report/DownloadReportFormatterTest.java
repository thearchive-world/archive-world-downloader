// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.ReportFixtures;

class DownloadReportFormatterTest {
    private static final Instant STARTED = Instant.parse("2026-06-22T07:14:01Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ReportEnvironment environment() {
        return ReportFixtures.environment("1.0.0-SNAPSHOT");
    }

    private static DownloadSession completed(Map<String, String> settings) {
        DownloadIdentity identity = ReportFixtures.identity("a", "survival.thearchive.world",
                "Updated to 26.1.2", "");
        return new DownloadSession(identity, settings, environment(), true, true, STARTED.plusSeconds(4),
                new DownloadCounts(421, 367, 0, ImmutableList.of(new DimensionChunks("overworld", 421))), null);
    }

    private static DownloadSession resumed(Instant startedAt, String id) {
        DownloadIdentity identity = new DownloadIdentity(id, startedAt, "Steve", "",
                "survival.thearchive.world", "", "", "Fabric", "1", "", "");
        return new DownloadSession(identity, Collections.<String, String>emptyMap(), environment(), true,
                true, startedAt.plusSeconds(4),
                new DownloadCounts(421, 367, 0, ImmutableList.of(new DimensionChunks("overworld", 421))),
                new SaveChunks(1000, ImmutableList.of(new DimensionChunks("overworld", 800),
                        new DimensionChunks("the_nether", 200))));
    }

    @Test
    void rendersTheRichLayoutForOneCompletedDownload() {
        Map<String, String> settings = ReportFixtures.settings();

        String md = DownloadReportFormatter.render(
                Collections.singletonList(completed(settings)), true, UTC, Locale.ROOT);

        assertTrue(md.startsWith("# Download report: Survival"), "title is the server name");
        assertTrue(md.contains("<img src=\"../icon.png\""), "the icon img is emitted when present");
        assertTrue(md.contains("epoch 1782112445"), "epoch seconds of the finish instant");
        assertTrue(md.contains("- **Duration**: 0m 4s"));
        assertTrue(md.contains("- **Status**: Completed cleanly"));
        assertTrue(md.contains("## Server"));
        assertTrue(md.contains("- **Server software**: Paper"));
        assertTrue(md.contains("- **Simulation distance**: 8 chunks"));
        assertTrue(md.contains("`overworld` (421 chunks)"));
        assertTrue(md.contains("- **Chunks**: 421"));
        assertTrue(md.contains("- **Entities**: 367"), "the entity count lands on its own summary line");
        assertTrue(md.contains("- **Containers**: 0"), "the container count lands on its own summary line");
        assertTrue(md.contains("- **Minecraft**: 1.21.11"));
        assertTrue(md.contains("- `debug.log_saved_chunks`: true"));
        assertFalse(md.contains("Players"), "the Players line is never rendered");
        assertTrue(md.contains("github.com/thearchive-world/archive-world-downloader"));
    }

    @Test
    void omitsTheImgWhenNoIconPresent() {
        String md = DownloadReportFormatter.render(
                Collections.singletonList(completed(Collections.<String, String>emptyMap())), false, UTC,
                Locale.ROOT);
        assertFalse(md.contains("<img"), "no img line without an icon");
    }

    @Test
    void rendersDefaultsWhenNoSettingsChanged() {
        String md = DownloadReportFormatter.render(
                Collections.singletonList(completed(Collections.<String, String>emptyMap())), false, UTC,
                Locale.ROOT);
        assertTrue(md.contains("Defaults"));
    }

    @Test
    void rendersAnInterruptedDownload() {
        DownloadIdentity identity = new DownloadIdentity("i", STARTED, "Terbin", "uuid", "addr", "name",
                "motd", "NeoForge", "21.11.42", "", "");
        DownloadSession pending = new DownloadSession(identity, Collections.<String, String>emptyMap(), environment(),
                false, false, null, null, null);

        String md = DownloadReportFormatter.render(Collections.singletonList(pending), false, UTC, Locale.ROOT);

        assertTrue(md.contains("- **Status**: Interrupted"));
        assertTrue(md.contains("- **Finished**: Unknown"));
        assertTrue(md.contains("- **Duration**: Unknown"));
        assertTrue(md.contains("- **Dimensions**: 1"), "an interrupted session still reports its begin-time dimension");
        assertTrue(md.contains("  - `overworld`"), "the pending record's dimension is named");
        assertTrue(md.contains("- **Chunks**: Unknown"));
        assertTrue(md.contains("- **Entities**: Unknown"));
        assertTrue(md.contains("- **Containers**: Unknown"));
    }

    @Test
    void rendersHoursInTheDurationPastAnHour() {
        DownloadIdentity identity = ReportFixtures.identity("d", "survival.thearchive.world", "", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(3723),
                new DownloadCounts(9, 9, 0, ImmutableList.of(new DimensionChunks("overworld", 9))), null);

        String md = DownloadReportFormatter.render(
                Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertTrue(md.contains("- **Duration**: 1h 02m 03s"),
                "past an hour the duration carries padded minutes and seconds");
    }

    @Test
    void rendersTheMostRecentWhenSeveralExist() {
        DownloadSession older = completed(Collections.<String, String>emptyMap());
        DownloadIdentity newerId = new DownloadIdentity("z", Instant.parse("2026-06-22T09:00:00Z"), "Terbin",
                "uuid", "addr", "Newer", "motd", "NeoForge", "21.11.42", "", "");
        DownloadSession newer = new DownloadSession(newerId, Collections.<String, String>emptyMap(), environment(),
                true, true, Instant.parse("2026-06-22T09:00:30Z"), new DownloadCounts(9, 9, 0), null);

        String md = DownloadReportFormatter.render(ImmutableList.of(older, newer), false, UTC, Locale.ROOT);

        assertTrue(md.startsWith("# Download report: Newer"), "the most recent download is rendered");
    }

    @Test
    void rendersTheTrueDimensionCountAndPerDimensionChunks() {
        DownloadIdentity identity = ReportFixtures.identity("m", "survival.thearchive.world", "", "");
        DownloadCounts counts = new DownloadCounts(430, 12, 3,
                ImmutableList.of(new DimensionChunks("overworld", 400), new DimensionChunks("the_nether", 30)));
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4), counts, null);

        String md = DownloadReportFormatter.render(
                Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertTrue(md.contains("- **Dimensions**: 2"), "the true dimension count is rendered");
        assertTrue(md.contains("`overworld` (400 chunks)"));
        assertTrue(md.contains("`the_nether` (30 chunks)"));
        assertTrue(md.contains("- **Chunks**: 430"), "the total sums the per-dimension counts");
    }

    @Test
    void rendersThePartialStatusWhenNotClean() {
        DownloadIdentity identity = ReportFixtures.identity("p", "survival.thearchive.world", "", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, false, STARTED.plusSeconds(4),
                new DownloadCounts(10, 1, 0, ImmutableList.of(new DimensionChunks("overworld", 10))), null);

        String md = DownloadReportFormatter.render(
                Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertTrue(md.contains("- **Status**: Completed with errors (partial)"));
    }

    @Test
    void escapesHostileServerText() {
        DownloadIdentity hostile = new DownloadIdentity("h", STARTED, "Terbin", "uuid", "addr",
                "Name](http://x)", "Line1\nLine2", "NeoForge", "21.11.42", "", "");
        ReportEnvironment hostileEnvironment = new ReportEnvironment("§cPaper", 8, "overworld", "1.21.11", "1.0.0");
        DownloadSession session = new DownloadSession(hostile, Collections.<String, String>emptyMap(),
                hostileEnvironment, true, true, STARTED.plusSeconds(1), new DownloadCounts(1, 1, 0), null);

        String md = DownloadReportFormatter.render(Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertFalse(md.contains("§c"), "section codes are stripped from server text");
        assertFalse(md.contains("Line1\nLine2"), "a MOTD newline cannot inject extra lines");
        assertTrue(md.contains("\\]"), "the server name's bracket is escaped");
    }

    @Test
    void namesAnUnidentifiedSourceInTheTitle() {
        DownloadIdentity identity = new DownloadIdentity("u", STARTED, "Terbin", "uuid", "", "", "",
                "NeoForge", "21.11.42", "replay-2026-07-19", "unidentified");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4), new DownloadCounts(9, 9, 0), null);

        String md = DownloadReportFormatter.render(
                Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertTrue(md.startsWith("# Download report: Unidentified source"), "the marker names the source");
    }

    // A nameless server still falls back to its address, so the marker must not swallow that branch.
    @Test
    void anEmptyNameStillFallsBackToTheAddress() {
        DownloadIdentity identity = new DownloadIdentity("a", STARTED, "Terbin", "uuid",
                "play.example.com", "", "", "NeoForge", "21.11.42", "w", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4), new DownloadCounts(9, 9, 0), null);

        String md = DownloadReportFormatter.render(
                Collections.singletonList(session), false, UTC, Locale.ROOT);

        assertTrue(md.startsWith("# Download report: play.example.com"), "the address branch survives");
    }

    @Test
    void singleSessionSummaryKeepsTheFlatShape() {
        DownloadIdentity identity = ReportFixtures.identity("a", "survival.thearchive.world", "", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4),
                new DownloadCounts(421, 367, 0, ImmutableList.of(new DimensionChunks("overworld", 421))),
                new SaveChunks(421, ImmutableList.of(new DimensionChunks("overworld", 421))));
        String md = DownloadReportFormatter.render(Collections.singletonList(session), false, UTC, Locale.ROOT);
        assertFalse(md.contains("### This download"), "matching totals render no tiers");
        assertFalse(md.contains("### In the save"));
        assertFalse(md.contains("## Downloads"), "one completed record renders no history table");
        assertTrue(md.contains("- **Chunks**: 421"));
    }

    @Test
    void zeroSaveTotalHidesTheInSaveTier() {
        DownloadIdentity identity = ReportFixtures.identity("a", "survival.thearchive.world", "", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4),
                new DownloadCounts(421, 367, 0, Collections.<DimensionChunks>emptyList()),
                new SaveChunks(0, Collections.<DimensionChunks>emptyList()));
        String md = DownloadReportFormatter.render(Collections.singletonList(session), false, UTC, Locale.ROOT);
        assertFalse(md.contains("### In the save"), "a failed scan must not headline 0 chunks");
    }

    @Test
    void undercountedSaveTotalHidesTheInSaveTier() {
        // A partially failed scan can undercount below the session's own chunks; a cumulative line smaller
        // than the session line would contradict the report, so the tier gates on exceeding, not differing.
        DownloadIdentity identity = ReportFixtures.identity("a", "survival.thearchive.world", "", "");
        DownloadSession session = new DownloadSession(identity, Collections.<String, String>emptyMap(),
                environment(), true, true, STARTED.plusSeconds(4),
                new DownloadCounts(421, 367, 0, Collections.<DimensionChunks>emptyList()),
                new SaveChunks(300, Collections.<DimensionChunks>emptyList()));
        String md = DownloadReportFormatter.render(Collections.singletonList(session), false, UTC, Locale.ROOT);
        assertFalse(md.contains("### In the save"));
    }

    @Test
    void resumedSummaryRendersBothTiersAnchored() {
        String md = DownloadReportFormatter.render(
                Collections.singletonList(resumed(STARTED, "a")), false, UTC, Locale.ROOT);
        int thisDownload = md.indexOf("### This download");
        int inSave = md.indexOf("### In the save");
        assertTrue(thisDownload > 0 && inSave > thisDownload, "both tiers render, session first");
        int sessionChunks = md.indexOf("- **Chunks**: 421");
        int saveChunks = md.indexOf("- **Chunks**: 1000");
        assertTrue(sessionChunks > thisDownload && sessionChunks < inSave, "session chunks in the session tier");
        assertTrue(saveChunks > inSave, "save total inside the in-save tier");
        assertTrue(md.indexOf("`the_nether` (200 chunks)") > inSave, "in-save breakdown inside its tier");
        assertTrue(md.indexOf("- **Entities**: 367") < inSave, "entities stay session-scoped");
    }

    @Test
    void rendersTheDownloadHistoryOldestFirstAtTwoRecords() {
        DownloadSession older = new DownloadSession(
                new DownloadIdentity("old", STARTED.minusSeconds(3600), "Steve", "",
                        "survival.thearchive.world", "", "", "Fabric", "1", "", ""),
                Collections.<String, String>emptyMap(), environment(), true, false,
                STARTED.minusSeconds(3600).plusSeconds(10),
                new DownloadCounts(100, 50, 5, Collections.<DimensionChunks>emptyList()),
                new SaveChunks(100, Collections.<DimensionChunks>emptyList()));
        String md = DownloadReportFormatter.render(ImmutableList.of(resumed(STARTED, "a"), older), false, UTC,
                Locale.ROOT);
        assertTrue(md.contains("## Downloads"));
        assertTrue(md.contains("| Finished | Duration | Chunks | Entities | Containers | Status |"));
        assertTrue(md.contains("| 100 | 50 | 5 | Partial |"), "the older session's row with its status");
        assertTrue(md.contains("| 421 | 367 | 0 | Clean |"), "the newest session's row");
        assertTrue(md.indexOf("| 100 | 50 | 5 |") < md.indexOf("| 421 | 367 | 0 |"), "oldest row first");
        int downloads = md.indexOf("## Downloads");
        assertTrue(downloads > md.indexOf("## Summary") && downloads < md.indexOf("## Software"),
                "history sits between Summary and Software");
    }

    @Test
    void historySkipsCompletedRecordMissingItsCompletionData() {
        // Only constructible directly (readRecord never yields complete with null completion data): the
        // row-inclusion checks exist because the fields are nullable on DownloadSession, and this test keeps
        // them exercised (without them the render NPEs on localTime.format).
        DownloadSession damaged = new DownloadSession(
                new DownloadIdentity("x", STARTED.plusSeconds(50), "Steve", "",
                        "survival.thearchive.world", "", "", "Fabric", "1", "", ""),
                Collections.<String, String>emptyMap(), environment(), true, true, null, null, null);
        DownloadSession older = new DownloadSession(
                new DownloadIdentity("old", STARTED.minusSeconds(3600), "Steve", "",
                        "survival.thearchive.world", "", "", "Fabric", "1", "", ""),
                Collections.<String, String>emptyMap(), environment(), true, true,
                STARTED.minusSeconds(3600).plusSeconds(10),
                new DownloadCounts(100, 50, 5, Collections.<DimensionChunks>emptyList()),
                new SaveChunks(100, Collections.<DimensionChunks>emptyList()));
        String md = DownloadReportFormatter.render(ImmutableList.of(damaged, resumed(STARTED, "a"), older), false,
                UTC, Locale.ROOT);
        assertTrue(md.contains("## Downloads"), "the two intact rows still render");
        assertTrue(md.contains("| 421 | 367 | 0 | Clean |"));
        assertTrue(md.contains("| 100 | 50 | 5 | Clean |"));
    }

    @Test
    void interruptedLatestKeepsThePriorHistoryAndNoInSaveTier() {
        DownloadSession pending = new DownloadSession(
                new DownloadIdentity("p", STARTED.plusSeconds(9000), "Steve", "",
                        "survival.thearchive.world", "", "", "Fabric", "1", "", ""),
                Collections.<String, String>emptyMap(), environment(), false, false, null, null, null);
        DownloadSession older = new DownloadSession(
                new DownloadIdentity("old", STARTED.minusSeconds(3600), "Steve", "",
                        "survival.thearchive.world", "", "", "Fabric", "1", "", ""),
                Collections.<String, String>emptyMap(), environment(), true, true,
                STARTED.minusSeconds(3600).plusSeconds(10),
                new DownloadCounts(100, 50, 5, Collections.<DimensionChunks>emptyList()),
                new SaveChunks(100, Collections.<DimensionChunks>emptyList()));
        String md = DownloadReportFormatter.render(ImmutableList.of(pending, resumed(STARTED, "a"), older), false,
                UTC, Locale.ROOT);
        assertTrue(md.contains("- **Chunks**: Unknown"), "the interrupted latest renders the Unknown summary");
        assertFalse(md.contains("### In the save"), "no in-save tier while the latest is interrupted");
        assertTrue(md.contains("## Downloads"), "the two prior completed rows still tell the story");
        assertTrue(md.contains("| 421 | 367 | 0 | Clean |"));
    }
}
