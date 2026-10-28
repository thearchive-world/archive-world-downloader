// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import world.thearchive.wdl.core.WorldType;

final class WorldgenWarmup {
    private static final Logger LOGGER = Logger.getLogger(WorldgenWarmup.class.getName());

    private WorldgenWarmup() {}

    static void dispatchForScreenOpen(WorldType worldType, Runnable warmup, Executor worker) {
        // Below 1.19 the void generator also reconstructs, since on Forge its biome needs a registry name the client's
        // synced biome lacks, so it warms too. This matches LevelDataWriter's own reconstruction branch exactly.
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
