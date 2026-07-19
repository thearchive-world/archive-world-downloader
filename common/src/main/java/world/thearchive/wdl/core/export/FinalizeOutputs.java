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

/**
 * The MC-free orchestration of a download's finalize-time zip outputs, so the knob and mode decisions stay
 * headless-testable while the adapter runs these on the writer thread.
 *
 * <ul>
 * <li>{@link #backupBeforeResume} takes the pre-merge safety copy: on a {@link DownloadMode#RESUME} (only), and only
 * when {@code zipOnResume} is on, it zips the existing folder to {@code <folder>-pre-resume.zip} before the merge
 * modifies it in place.</li>
 * <li>{@link #exportZip} writes the finish-time export zip {@code <folder>.zip} when {@code zipOnFinish} is on. The
 * folder's on-disk size is deliberately not recorded at finish: the download screen reads each row's size by walking
 * the folder on open, so the size always reflects the disk.</li>
 * </ul>
 *
 * <p>Fail-soft: a failing zip is cleaned up by {@link FolderZipper} and only ever reads the openable folder, so it is
 * caught and surfaced here, never propagated to fail the save.
 */
public final class FinalizeOutputs {
    private static final Logger LOGGER = Logger.getLogger(FinalizeOutputs.class.getName());

    private FinalizeOutputs() {}

    /** Zip the existing folder to {@code <folder>-pre-resume.zip} before a resume merges into it. */
    public static void backupBeforeResume(Path saveFolder, DownloadMode mode, boolean zipOnResume) {
        if (!zipOnResume || mode != DownloadMode.RESUME) {
            return;
        }
        Path target = ZipName.nextFreeBackup(savesDirectory(saveFolder), folderName(saveFolder));
        try {
            FolderZipper.zip(saveFolder, target);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "the resume backup zip failed; the resume proceeds without a safety copy", e);
        }
    }

    /**
     * Write the finish-time export zip {@code <folder>.zip} when enabled; a no-op when the knob is off. Returns the
     * written zip's filename, or null when none was written (knob off, or the zip failed). Drives {@code progress}
     * through the {@link SaveProgress#compressing} phase so the HUD bar advances over the folder's on-disk byte total
     * as each file is archived. The byte total is the pre-zip folder size; a folder whose size cannot be read reports a
     * zero-total phase, which shows no fraction.
     */
    public static @Nullable String exportZip(Path saveFolder, boolean zipOnFinish, SaveProgress progress) {
        if (!zipOnFinish) {
            return null;
        }
        Path target = ZipName.nextFreeExport(savesDirectory(saveFolder), folderName(saveFolder));
        OptionalLong size = FolderSize.onDiskSize(saveFolder);
        long byteTotal = size.orElse(0L);
        progress.compressing(0, byteTotal);
        try {
            FolderZipper.zip(saveFolder, target, bytesZipped -> progress.compressing(bytesZipped, byteTotal));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "the export zip failed; the openable folder is intact", e);
            return null;
        }
        return target.getFileName().toString();
    }

    /** The saves directory the zips land in (beside the folder); never null for a {@code saves/<folder>} path. */
    private static Path savesDirectory(Path saveFolder) {
        Path parent = saveFolder.getParent();
        return parent != null ? parent : saveFolder;
    }

    /** The download's folder name, the base the zip names derive from (already sanitized and contained). */
    private static String folderName(Path saveFolder) {
        Path name = saveFolder.getFileName();
        return name != null ? name.toString() : saveFolder.toString();
    }
}
