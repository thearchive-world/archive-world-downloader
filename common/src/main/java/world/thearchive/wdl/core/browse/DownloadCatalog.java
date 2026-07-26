// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.report.DownloadCounts;
import world.thearchive.wdl.core.report.DownloadReportLog;
import world.thearchive.wdl.core.report.DownloadSession;
import world.thearchive.wdl.core.report.SaveChunks;
import world.thearchive.wdl.core.report.WorldIconWriter;

/**
 * Lists the wdl-managed downloads under the saves directory as {@link DownloadEntry} rows, reading each one's summary,
 * health, and icon from its {@code wdl/} record and siblings without walking the folder; the row's size is filled in
 * later by the screen's own on-disk walk. MC-free and headless: it is handed the saves directory and the
 * currently-loaded world path; the client facts come from the caller.
 */
public final class DownloadCatalog {
    private static final Logger LOGGER = Logger.getLogger(DownloadCatalog.class.getName());

    private DownloadCatalog() {}

    /** The wdl-managed downloads under {@code savesDirectory}, most-recently-played first. */
    public static List<DownloadEntry> list(Path savesDirectory, @Nullable Path loadedWorld) throws IOException {
        List<DownloadEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(savesDirectory)) {
            return entries;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(savesDirectory)) {
            for (Path folder : folders) {
                if (DownloadFolders.isWdlManaged(folder)) {
                    // Isolate per folder: one unreadable download must not hide every other from the list.
                    try {
                        entries.add(toEntry(folder, loadedWorld));
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.WARNING, "skipping an unreadable download folder: " + folder, e);
                    }
                }
            }
        }
        entries.sort((first, second) -> Long.compare(second.lastPlayedEpochMillis(),
                first.lastPlayedEpochMillis()));
        return entries;
    }

    private static DownloadEntry toEntry(Path folder, @Nullable Path loadedWorld) throws IOException {
        DownloadSession pending = null;
        DownloadSession latestComplete = null;
        for (DownloadSession session : DownloadReportLog.readDownloads(folder)) {
            if (!session.isComplete()) {
                pending = session; // a surviving crash sentinel (at most one per folder)
            } else if (isNewerThan(session, latestComplete)) {
                latestComplete = session;
            }
        }
        String folderName = folder.getFileName().toString();
        boolean currentlyLoaded = TargetResolver.isSameWorld(folder, loadedWorld);
        byte[] icon = readIcon(folder);
        boolean tainted = SinglePlayerTaint.isTainted(folder);

        // Complete only when a completion record exists and no live sentinel survives; otherwise recoverable,
        // which deliberately surfaces no summary. The size is not read here: the screen walks the folder.
        if (latestComplete != null && pending == null) {
            String worldName = worldName(latestComplete, folderName);
            DownloadHealth health = latestComplete.isClean() ? DownloadHealth.COMPLETE : DownloadHealth.PARTIAL;
            DownloadCounts sessionCounts = latestComplete.counts();
            SaveChunks saveChunks = latestComplete.saveChunks();
            DownloadCounts rowCounts = sessionCounts;
            boolean chunksOnly = false;
            // A resume knows its cumulative chunk total but not a cumulative entity or container count, so
            // it shows chunks only; a total not exceeding the session chunks (failed, empty, or undercounted
            // scan) falls back wholesale, never a smaller-than-session number. Both locals are null-checked
            // here so NullAway sees the guard before either is dereferenced.
            if (sessionCounts != null && saveChunks != null && saveChunks.total() > sessionCounts.chunks()) {
                rowCounts = new DownloadCounts(saveChunks.total(), sessionCounts.entities(),
                        sessionCounts.containers());
                chunksOnly = true;
            }
            return new DownloadEntry(folderName, worldName, DatedSuffix.strip(worldName),
                    epochMillis(latestComplete.finishedAt()), health, rowCounts,
                    icon, currentlyLoaded, chunksOnly, tainted);
        }
        long lastPlayed = pending != null ? pending.identity().startedAt().toEpochMilli()
                : Files.getLastModifiedTime(folder).toMillis();
        String worldName = worldName(pending, folderName);
        return new DownloadEntry(folderName, worldName, DatedSuffix.strip(worldName), lastPlayed,
                DownloadHealth.RECOVERABLE, null, icon, currentlyLoaded, false, tainted);
    }

    private static boolean isNewerThan(DownloadSession candidate, @Nullable DownloadSession incumbent) {
        return incumbent == null || epochMillis(candidate.finishedAt()) > epochMillis(incumbent.finishedAt());
    }

    private static long epochMillis(@Nullable Instant instant) {
        return instant != null ? instant.toEpochMilli() : 0L;
    }

    /** The world's full level.dat name: the recorded download name, or the folder name when none was recorded. */
    private static String worldName(@Nullable DownloadSession session, String folderName) {
        if (session == null) {
            return folderName;
        }
        String recorded = session.identity().downloadName();
        return recorded.isEmpty() ? folderName : recorded;
    }

    private static byte @Nullable [] readIcon(Path folder) throws IOException {
        Path iconFile = WorldIconWriter.iconFile(folder);
        if (!Files.isRegularFile(iconFile) || Files.size(iconFile) > WorldIcon.MAX_BYTES) {
            return null;
        }
        return WorldIcon.validate(Files.readAllBytes(iconFile));
    }
}
