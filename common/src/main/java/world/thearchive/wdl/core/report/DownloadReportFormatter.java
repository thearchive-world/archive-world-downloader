// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import world.thearchive.wdl.core.ElapsedTime;

/**
 * Renders the most recent download as the rich human-readable Markdown report: a titled header with the finish times,
 * duration, and status, then Server / Summary / Software / Settings sections, plus a Downloads history table once a
 * save holds more than one completed download. Server-controlled text (the title source, address, MOTD, and server
 * brand) is passed through {@link ReportText#escapeServerText(String)} so a hostile server cannot inject markup or
 * extra report lines; mod-generated copy (labels, the image tag, the footer) is written as authored. The machine record
 * stays ISO-8601 UTC; this rendering shows the completion instant in local, UTC, and epoch form, so its timestamps are
 * timezone-dependent by design. The injected {@code zone} and {@code locale} make the local form deterministic for
 * tests.
 */
final class DownloadReportFormatter {
    private static final DateTimeFormatter utcTime = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private DownloadReportFormatter() {}

    /** Render the most recent of {@code downloads} as the rich report; empty input renders nothing. */
    static String render(List<DownloadSession> downloads, boolean iconPresent, ZoneId zone,
            Locale locale) {
        if (downloads.isEmpty()) {
            return "";
        }
        List<DownloadSession> ordered = new ArrayList<>(downloads);
        ordered.sort((left, right) -> right.identity().startedAt().compareTo(left.identity().startedAt()));
        DownloadSession latest = ordered.get(0);

        DateTimeFormatter localTime = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm (z)", locale).withZone(zone);
        StringBuilder out = new StringBuilder();
        appendHeader(out, latest, iconPresent, localTime);
        appendServer(out, latest);
        appendSummary(out, latest);
        appendHistory(out, ordered, localTime);
        appendSoftware(out, latest);
        appendSettings(out, latest.settings());
        out.append("\n---\n*Made with [Archive World Downloader]")
                .append("(https://github.com/thearchive-world/archive-world-downloader).*\n");
        return out.toString();
    }

    private static void appendHeader(StringBuilder out, DownloadSession session, boolean iconPresent,
            DateTimeFormatter localTime) {
        DownloadIdentity identity = session.identity();
        String titleSource = identity.sourceName().isEmpty() ? identity.sourceAddress() : identity.sourceName();
        if (titleSource.isEmpty() && !identity.sourceKind().isEmpty()) {
            // Mod-authored copy, so it does not go through escapeServerText, which is for untrusted server text.
            out.append("# Download report: Unidentified source\n\n");
        } else {
            out.append("# Download report: ").append(ReportText.escapeServerText(titleSource)).append("\n\n");
        }
        if (iconPresent) {
            out.append("<img src=\"../icon.png\" alt=\"Server icon\">\n\n");
        }
        Instant finishedAt = session.finishedAt();
        if (session.isComplete() && finishedAt != null) {
            out.append("- Finished: ").append(localTime.format(finishedAt)).append(" | ")
                    .append(utcTime.format(finishedAt)).append(" | epoch ")
                    .append(finishedAt.getEpochSecond()).append('\n');
        } else {
            out.append("- Finished: Unknown\n");
        }
        out.append("- Downloaded by: ").append(ReportText.escapeServerText(identity.downloaderName()))
                .append('\n');
        if (session.isComplete() && finishedAt != null) {
            out.append("- Duration: ").append(formatDuration(identity.startedAt(), finishedAt)).append('\n');
        } else {
            out.append("- Duration: Unknown\n");
        }
        out.append("- Status: ").append(statusText(session)).append('\n');
    }

    private static void appendServer(StringBuilder out, DownloadSession session) {
        DownloadIdentity identity = session.identity();
        out.append("\n## Server\n");
        out.append("- Address: ").append(ReportText.escapeServerText(identity.sourceAddress())).append('\n');
        out.append("- MOTD: ").append(ReportText.escapeServerText(identity.sourceMotd())).append('\n');
        ReportEnvironment environment = session.environment();
        if (environment != null) {
            String brand = ReportText.escapeServerText(environment.serverBrand());
            if (!brand.isEmpty()) {
                out.append("- Server software: ").append(brand).append('\n');
            }
            out.append("- Simulation distance: ").append(environment.simulationDistance())
                    .append(" chunks\n");
        }
    }

    private static void appendSummary(StringBuilder out, DownloadSession session) {
        DownloadCounts counts = session.counts();
        out.append("\n## Summary\n");
        if (counts != null) {
            // Tiered only when the save total exceeds the session chunks: an equal (single-session),
            // zero, or undercounted scan total adds nothing over the session line and must not headline.
            SaveChunks saveChunks = session.saveChunks();
            SaveChunks tieredSave = saveChunks != null && saveChunks.total() > counts.chunks()
                    ? saveChunks
                    : null;
            if (tieredSave != null) {
                out.append("\n### This download\n");
            }
            appendDimensionBreakdown(out, counts.dimensions());
            out.append("- Chunks: ").append(counts.chunks()).append('\n');
            out.append("- Entities: ").append(counts.entities()).append('\n');
            out.append("- Containers: ").append(counts.containers()).append('\n');
            if (tieredSave != null) {
                out.append("\n### In the save\n");
                appendDimensionBreakdown(out, tieredSave.dimensions());
                out.append("- Chunks: ").append(tieredSave.total()).append('\n');
            }
        } else {
            // Interrupted: no completion counts, only the begin-time dimension the pending record holds.
            ReportEnvironment environment = session.environment();
            out.append("- Dimensions: 1\n");
            if (environment != null) {
                out.append("  - `").append(environment.dimensionName()).append("`\n");
            }
            out.append("- Chunks: Unknown\n");
            out.append("- Entities: Unknown\n");
            out.append("- Containers: Unknown\n");
        }
    }

    private static void appendDimensionBreakdown(StringBuilder out, List<DimensionChunks> dimensions) {
        out.append("- Dimensions: ").append(dimensions.size()).append('\n');
        for (DimensionChunks dimension : dimensions) {
            out.append("  - `").append(dimension.dimensionName()).append("` (")
                    .append(dimension.chunks()).append(" chunks)\n");
        }
    }

    /**
     * The completed downloads as one table row each, oldest first; absent below two rows. One pass, so the null checks
     * gate row inclusion directly rather than re-checking a prefiltered list.
     */
    private static void appendHistory(StringBuilder out, List<DownloadSession> newestFirst,
            DateTimeFormatter localTime) {
        List<String> rows = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            DownloadSession session = newestFirst.get(i);
            Instant finishedAt = session.finishedAt();
            DownloadCounts counts = session.counts();
            if (finishedAt == null || counts == null) {
                continue; // interrupted, or a completed record missing its completion data
            }
            rows.add("| " + localTime.format(finishedAt) + " | "
                    + formatDuration(session.identity().startedAt(), finishedAt) + " | " + counts.chunks()
                    + " | " + counts.entities() + " | " + counts.containers() + " | "
                    + (session.isClean() ? "Clean" : "Partial") + " |\n");
        }
        if (rows.size() < 2) {
            return;
        }
        out.append("\n## Downloads\n");
        out.append("| Finished | Duration | Chunks | Entities | Containers | Status |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (String row : rows) {
            out.append(row);
        }
    }

    private static void appendSoftware(StringBuilder out, DownloadSession session) {
        DownloadIdentity identity = session.identity();
        ReportEnvironment environment = session.environment();
        out.append("\n## Software\n");
        if (environment != null) {
            out.append("- Archive World Downloader: ").append(environment.modVersion()).append('\n');
            out.append("- Minecraft: ").append(environment.minecraftVersion()).append('\n');
        }
        out.append("- Loader: ").append(identity.loaderName()).append(' ')
                .append(identity.loaderVersion()).append('\n');
    }

    private static void appendSettings(StringBuilder out, Map<String, String> settings) {
        out.append("\n## Settings at download time\n");
        if (settings.isEmpty()) {
            out.append("- Defaults\n");
            return;
        }
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            out.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append('\n');
        }
    }

    private static String statusText(DownloadSession session) {
        if (!session.isComplete()) {
            return "Interrupted";
        }
        return session.isClean() ? "Completed cleanly" : "Completed with errors (partial)";
    }

    private static String formatDuration(Instant start, Instant finish) {
        ElapsedTime elapsed = ElapsedTime.ofSeconds(Duration.between(start, finish).getSeconds());
        if (elapsed.hours() > 0) {
            return elapsed.hours() + "h " + ElapsedTime.pad2(elapsed.minutes()) + "m "
                    + ElapsedTime.pad2(elapsed.seconds()) + "s";
        }
        return elapsed.minutes() + "m " + elapsed.seconds() + "s";
    }
}
