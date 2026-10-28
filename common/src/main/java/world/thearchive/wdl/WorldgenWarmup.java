// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import world.thearchive.wdl.core.WorldType;

/**
 * Only the manual (screen-open) path warms. The screen opens while connected, so the reconstruction's transitively-read
 * item tags are bound. The auto-download path cannot be warmed pre-join: the reconstruction needs those tags, which a
 * client connected to a remote server binds only on joining it.
 */
final class WorldgenWarmup {
    private static final Logger LOGGER = Logger.getLogger(WorldgenWarmup.class.getName());

    private WorldgenWarmup() {}

    static void dispatchForScreenOpen(WorldType worldType, Runnable warmup, Executor worker) {
        if (worldType.generatesTerrain()) {
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
