// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import world.thearchive.wdl.core.WorldType;

/**
 * Pre-download dispatch for the client-side worldgen reconstruction: when the download screen opens, submit the band's
 * warmup to a background worker so the first DEFAULT/FLAT download finds the registries already built instead of
 * decoding them on the render thread. The gate is {@link WorldType#needsWorldgenReconstruction()}, shared with the
 * level-data build so the two cannot drift; dispatch is idempotent through the reconstruction's own memo, so a repeated
 * trigger just hits the cache.
 *
 * <p>Only the manual (screen-open) path warms. The screen opens while connected, so the reconstruction's
 * transitively-read item tags are bound. The auto-download path cannot be warmed pre-join: the reconstruction needs
 * those tags, which a client binds only on server join, and the auto-download itself fires at join, so there is no
 * pre-flush window in which a warmup could complete.
 *
 * <p>This helper names no {@code net.minecraft} type, so it logs through java.util.logging to stay MC-free (the same
 * reason core/ does); the loader's {@link CoreLogHandler} bridge forwards the {@code world.thearchive.wdl} namespace to
 * the MC log, so the one failure line still reaches latest.log, and the test can capture it with a plain
 * java.util.logging Handler.
 */
final class WorldgenWarmup {
    private static final Logger LOGGER = Logger.getLogger(WorldgenWarmup.class.getName());

    private WorldgenWarmup() {}

    /** Warm when the download screen opens, if the chosen generator needs worldgen. */
    static void dispatchForScreenOpen(WorldType worldType, Runnable warmup, Executor worker) {
        if (worldType.needsWorldgenReconstruction()) {
            submit(warmup, worker);
        }
    }

    private static void submit(Runnable warmup, Executor worker) {
        worker.execute(() -> {
            try {
                warmup.run();
            } catch (Throwable thrown) {
                // Never poison the download: a failed warmup leaves the memo cold, so the render thread's own
                // reconstruction at first flush re-attempts and surfaces the real failure. Log once and return.
                LOGGER.log(Level.WARNING,
                        "worldgen registry warmup failed; the download will reconstruct it on the render thread",
                        thrown);
            }
        });
    }
}
