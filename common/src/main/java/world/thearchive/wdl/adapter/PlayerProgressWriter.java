// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.AtomicFileWrite;

/**
 * Writes the player's progress surface ({@code stats/<uuid>.json}) under the save root from an immutable
 * {@link CapturedProgress}, on the writer thread after level.dat. It is written fail-soft: a throw is logged and
 * swallowed, never propagated, because this runs inside the {@link AsyncSaveWriter} finalizer where a throw would mark
 * the whole save FAILED and skip the backup zip. A non-essential fidelity surface must never fail an otherwise-complete
 * download. The write goes through {@link AtomicFileWrite} so a swallowed failure cannot also destroy the copy a resume
 * found on disk. A null blob skips the file (config-off, or no stats reply landed); a null snapshot is a no-op (the
 * disconnect-flush path). There is no advancements file at this band: advancements arrive at 1.12.
 */
final class PlayerProgressWriter {
    private static final Logger LOGGER = LogManager.getLogger(PlayerProgressWriter.class);

    private PlayerProgressWriter() {}

    static void write(Path saveRoot, @Nullable CapturedProgress progress) {
        if (progress == null) {
            return;
        }
        String fileName = progress.playerUuid() + ".json";
        if (progress.statsJson() != null) {
            writeFile(saveRoot.resolve("stats"), fileName, progress.statsJson());
        }
    }

    private static void writeFile(Path directory, String fileName, byte[] bytes) {
        try {
            AtomicFileWrite.write(directory.resolve(fileName), bytes);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to write the player progress file {}", directory.resolve(fileName), e);
        }
    }
}
