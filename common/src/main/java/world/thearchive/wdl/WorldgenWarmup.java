// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import world.thearchive.wdl.core.WorldType;

/**
 * Pre-download dispatch for the band's worldgen warmup: when the download screen opens, submit it to a background
 * worker. Below 1.16 that warmup is empty.
 *
 * <p>This helper names no {@code net.minecraft} type, so it logs through java.util.logging to stay MC-free (the same
 * reason core/ does); the loader's {@link CoreLogHandler} bridge forwards the {@code world.thearchive.wdl} namespace to
 * the MC log, so the one failure line still reaches latest.log, and the test can capture it with a plain
 * java.util.logging Handler.
 */
final class WorldgenWarmup {
    private static final Logger LOGGER = Logger.getLogger(WorldgenWarmup.class.getName());

    private WorldgenWarmup() {}

    static void dispatchForScreenOpen(WorldType worldType, Runnable warmup, Executor worker) {
        if (worldType == WorldType.VOID || worldType.generatesTerrain()) {
            submit(warmup, worker);
        }
    }

    private static void submit(Runnable warmup, Executor worker) {
        worker.execute(() -> {
            try {
                warmup.run();
            } catch (Throwable thrown) {
                LOGGER.log(Level.WARNING,
                        "worldgen registry warmup failed; the download will reconstruct it on the render thread",
                        thrown);
            }
        });
    }
}
