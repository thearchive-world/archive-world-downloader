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
 * Writes the player's progress surfaces ({@code stats/<uuid>.json}, {@code advancements/<uuid>.json}) under the save
 * root from an immutable {@link CapturedProgress}, on the writer thread after level.dat. Each file is written fail-soft
 * and per file: a throw is logged and swallowed, never propagated, because this runs inside the {@link AsyncSaveWriter}
 * finalizer where a throw would mark the whole save FAILED and skip the backup zip. A non-essential fidelity surface
 * must never fail an otherwise-complete download. Each file goes through {@link AtomicFileWrite} so a swallowed failure
 * cannot also destroy the copy a resume found on disk. A null blob skips its file (the stats null is config-off or no
 * stats reply landed; the advancements null is config-off or a per-surface fail-soft assembly error); a null snapshot
 * is a no-op (the disconnect-flush path).
 */
final class PlayerProgressWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerProgressWriter() {}

    static void write(Path saveRoot, @Nullable CapturedProgress progress) {
        if (progress == null) {
            return;
        }
        String fileName = progress.playerUuid() + ".json";
        // 26.x moved the player-adjacent directories under players/ (LevelResource PLAYER_ADVANCEMENTS_DIR /
        // PLAYER_STATS_DIR); vanilla reads them nowhere else. Band-local path in a shared file
        // (config/band-divergence.txt).
        if (progress.advancementsJson() != null) {
            writeFile(saveRoot.resolve("players").resolve("advancements"), fileName, progress.advancementsJson());
        }
        if (progress.statsJson() != null) {
            writeFile(saveRoot.resolve("players").resolve("stats"), fileName, progress.statsJson());
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
