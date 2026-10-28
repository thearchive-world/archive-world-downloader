// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.core.AtomicFileWrite;

/**
 * Each file is written fail-soft and per file: a throw is logged and swallowed, never propagated, because this runs
 * inside the {@link AsyncSaveWriter} finalizer where a throw would mark the whole save FAILED. Each file goes through
 * {@link AtomicFileWrite} so a swallowed failure cannot also destroy the copy a resume found on disk.
 */
final class PlayerProgressWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerProgressWriter() {}

    static void write(Path saveRoot, @Nullable CapturedProgress progress) {
        if (progress == null) {
            return;
        }
        String fileName = progress.playerUuid() + ".json";
        if (progress.advancementsJson() != null) {
            writeFile(saveRoot.resolve("advancements"), fileName, progress.advancementsJson());
        }
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
