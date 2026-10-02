// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.File;
import java.nio.file.Path;

/**
 * The crash sentinel would plant a false crash signal in a backup.
 */
final class SaveWalk {
    static final String TEMPORARY_PREFIX = "wdl-export-";
    static final String TEMPORARY_SUFFIX = ".part";
    private static final String PENDING_SENTINEL = "wdl/download.pending";

    private SaveWalk() {}

    static boolean isExcluded(Path root, Path file) {
        return SessionLock.matches(file) || isTemporaryArtifact(file) || isPendingSentinel(root, file);
    }

    private static boolean isTemporaryArtifact(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String fileName = name.toString();
        return fileName.startsWith(TEMPORARY_PREFIX) && fileName.endsWith(TEMPORARY_SUFFIX);
    }

    private static boolean isPendingSentinel(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/').equals(PENDING_SENTINEL);
    }
}
