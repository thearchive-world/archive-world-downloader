// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.SaveProgress;

public final class FinalizeOutputs {
    private static final Logger LOGGER = Logger.getLogger(FinalizeOutputs.class.getName());

    private FinalizeOutputs() {}

    /** The file name the pre-resume backup of {@code folderName} would take right now, counter and all. */
    public static String nextBackupName(Path savesDirectory, String folderName) {
        return ZipName.nextFreeBackup(savesDirectory, folderName).getFileName().toString();
    }

    public static void backupBeforeResume(Path saveFolder, DownloadMode mode, boolean zipOnResume) {
        if (!zipOnResume || mode != DownloadMode.RESUME) {
            return;
        }
        Path folder = saveRoot(saveFolder);
        Path target = ZipName.nextFreeBackup(savesDirectory(folder), folderName(folder));
        try {
            FolderZipper.zip(folder, target);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "the resume backup zip failed; the resume proceeds without a safety copy", e);
        }
    }

    /**
     * Returns the written zip's filename, or null when none was written (knob off, or the zip failed).
     */
    public static @Nullable String exportZip(Path saveFolder, boolean zipOnFinish, SaveProgress progress) {
        if (!zipOnFinish) {
            return null;
        }
        Path folder = saveRoot(saveFolder);
        Path target = ZipName.nextFreeExport(savesDirectory(folder), folderName(folder));
        OptionalLong size = FolderSize.onDiskSize(folder);
        long byteTotal = size.orElse(0L);
        progress.compressing(0, byteTotal);
        try {
            FolderZipper.zip(folder, target, bytesZipped -> progress.compressing(bytesZipped, byteTotal));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "the export zip failed; the openable folder is intact", e);
            return null;
        }
        return target.getFileName().toString();
    }

    /**
     * The save root with any trailing dot component stripped, so the archive is named and placed beside the folder
     * rather than inside it.
     */
    private static Path saveRoot(Path saveFolder) {
        return saveFolder.normalize();
    }

    private static Path savesDirectory(Path saveFolder) {
        Path parent = saveFolder.getParent();
        return parent != null ? parent : saveFolder;
    }

    private static String folderName(Path saveFolder) {
        Path name = saveFolder.getFileName();
        return name != null ? name.toString() : saveFolder.toString();
    }
}
