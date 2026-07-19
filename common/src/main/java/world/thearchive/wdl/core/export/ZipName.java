// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the next free, never-overwriting name for a finalize-time zip beside the save folder. The un-suffixed name
 * is the oldest; a collision takes the next numeric suffix inserted before the {@code .zip} extension, counting from
 * {@code (2)}, leaving every existing file untouched. The export carries no suffix ({@code <folder>.zip}); the resume
 * backup carries {@code -pre-resume} ({@code <folder>-pre-resume.zip}) and the pre-restore snapshot carries
 * {@code -singleplayer} ({@code <folder>-singleplayer.zip}).
 *
 * <p>MC-free and band-agnostic. The base name is the download's folder name, already sanitized and contained to the
 * saves directory by the download screen; this only appends a suffix and counter, never a path separator, so the result
 * stays beside the folder in that directory.
 */
final class ZipName {
    private static final String EXTENSION = ".zip";
    static final String PRE_RESUME_SUFFIX = "-pre-resume";
    static final String SINGLEPLAYER_SUFFIX = "-singleplayer";

    private ZipName() {}

    /** The next free export name {@code <folder>.zip} (then {@code _(2)}, {@code _(3)} ...) in {@code directory}. */
    static Path nextFreeExport(Path directory, String folderName) {
        return nextFree(directory, folderName);
    }

    /**
     * The next free resume-backup name {@code <folder>-pre-resume.zip} (then {@code _(2)} ...) in {@code directory}.
     */
    static Path nextFreeBackup(Path directory, String folderName) {
        return nextFree(directory, folderName + PRE_RESUME_SUFFIX);
    }

    /** The next free pre-restore snapshot name {@code <folder>-singleplayer.zip} (then {@code _(2)} ...). */
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
