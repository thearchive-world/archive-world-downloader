// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DownloadReportStore {
    static final String SUBFOLDER = "wdl";
    static final String MACHINE_FILE = "download.jsonl";
    static final String PENDING_FILE = "download.pending";
    static final String HUMAN_FILE = "download.md";

    private static final Logger LOGGER = Logger.getLogger(DownloadReportStore.class.getName());

    private final Set<String> completed = new HashSet<>();

    /** Write the pending sentinel (the begin-time record) only; the rendering refresh is a separate step. */
    public synchronized void begin(Path saveRoot, DownloadIdentity identity, ReportEnvironment environment,
            Map<String, String> settings) {
        try {
            Path pending = reportFile(saveRoot, PENDING_FILE);
            Files.createDirectories(pending.getParent());
            Files.write(pending, (DownloadReportLog.pendingLine(identity, environment, settings) + "\n")
                    .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to write the download report start record", e);
        }
    }

    /** Regenerate the human {@code download.md} from the read model; fail-soft like every report write. */
    public synchronized void refreshHumanRendering(Path saveRoot) {
        try {
            regenerateHumanRendering(saveRoot);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to write the download report rendering", e);
        }
    }

    /**
     * Append the completed line once (at-most-once), drop the sentinel, and refresh the human rendering. The save
     * totals come in as a supplier evaluated only when the line is actually written, so the second driver never pays
     * the scan.
     */
    public synchronized void complete(Path saveRoot, DownloadIdentity identity, ReportEnvironment environment,
            Map<String, String> settings, Instant finishedAt, DownloadCounts counts,
            Supplier<SaveChunks> saveChunks, Map<String, Integer> losses) {
        if (completed.contains(identity.id())) {
            return;
        }
        try {
            // Append the durable completed line first, latch the id, then drop the sentinel: a delete
            // failure cannot let a second append through, and a stale sentinel is reconciled by id on read.
            DownloadReportLog.append(reportFile(saveRoot, MACHINE_FILE), DownloadReportLog.completedLine(
                    identity, environment, settings, finishedAt, counts, saveChunks.get(), losses));
            completed.add(identity.id());
            Files.deleteIfExists(reportFile(saveRoot, PENDING_FILE));
            regenerateHumanRendering(saveRoot);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to write the download report completion record", e);
        }
    }

    /** The machine record file ({@code wdl/download.jsonl}), for readers such as the download screen. */
    public static Path machineFile(Path saveRoot) {
        return reportFile(saveRoot, MACHINE_FILE);
    }

    /** The crash sentinel file ({@code wdl/download.pending}); its presence with no completed line is a crash. */
    public static Path pendingFile(Path saveRoot) {
        return reportFile(saveRoot, PENDING_FILE);
    }

    private void regenerateHumanRendering(Path saveRoot) throws IOException {
        List<DownloadSession> downloads = DownloadReportLog.readDownloads(
                reportFile(saveRoot, MACHINE_FILE), reportFile(saveRoot, PENDING_FILE));
        boolean iconPresent = Files.exists(WorldIconWriter.iconFile(saveRoot));
        String markdown = DownloadReportFormatter.render(downloads, iconPresent,
                ZoneId.systemDefault(), Locale.getDefault());
        Path humanFile = reportFile(saveRoot, HUMAN_FILE);
        Files.createDirectories(humanFile.getParent());
        Files.write(humanFile, markdown.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static Path reportFile(Path saveRoot, String fileName) {
        return saveRoot.resolve(SUBFOLDER).resolve(fileName);
    }
}
