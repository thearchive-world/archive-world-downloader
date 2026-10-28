// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.report.DownloadReportStore;

public final class DownloadFolders {
    private DownloadFolders() {}

    /** A folder is a wdl download when it carries a report record or a crash sentinel in its {@code wdl/} directory. */
    public static boolean isWdlManaged(Path folder) {
        return Files.isDirectory(folder)
                && (Files.exists(DownloadReportStore.machineFile(folder))
                        || Files.exists(DownloadReportStore.pendingFile(folder)));
    }

    /**
     * Validate a typed {@code /wdl resume} name and resolve it to a RESUME target, or null to reject. The name is
     * sanitized and contained to a single path component before the probe ({@code resolveResume} is verbatim and could
     * otherwise traverse).
     */
    public static @Nullable DownloadTarget resolveManagedResume(String typedName, Path savesDirectory) {
        String name = TargetResolver.sanitize(typedName);
        if (name.isEmpty() || !isWdlManaged(savesDirectory.resolve(name))) {
            return null;
        }
        return TargetResolver.resolveResume(name, savesDirectory);
    }

    /**
     * The names of the wdl-managed folders under {@code savesDirectory}, sorted. An empty list when the directory does
     * not exist.
     */
    public static List<String> listManaged(Path savesDirectory) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(savesDirectory)) {
            return names;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(savesDirectory)) {
            for (Path folder : folders) {
                Path name = folder.getFileName();
                if (name != null && isWdlManaged(folder)) {
                    names.add(name.toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }
}
