// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.nio.file.Files;
import java.nio.file.Path;

final class ZipName {
    private static final String EXTENSION = ".zip";
    static final String PRE_RESUME_SUFFIX = "-pre-resume";
    static final String SINGLEPLAYER_SUFFIX = "-singleplayer";

    private ZipName() {}

    static Path nextFreeExport(Path directory, String folderName) {
        return nextFree(directory, folderName);
    }

    static Path nextFreeBackup(Path directory, String folderName) {
        return nextFree(directory, folderName + PRE_RESUME_SUFFIX);
    }

    static Path nextFreeSinglePlayer(Path directory, String folderName) {
        return nextFree(directory, folderName + SINGLEPLAYER_SUFFIX);
    }

    private static Path nextFree(Path directory, String stem) {
        Path bare = directory.resolve(stem + EXTENSION);
        if (!Files.exists(bare)) {
            return bare;
        }
        for (int counter = 2;; counter++) {
            Path candidate = directory.resolve(stem + "_(" + counter + ")" + EXTENSION);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
