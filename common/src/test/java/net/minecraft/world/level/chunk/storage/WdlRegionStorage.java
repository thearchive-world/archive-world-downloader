// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package net.minecraft.world.level.chunk.storage;

import java.io.File;

/**
 * Test-only shim that opens a vanilla region IO worker over a directory. At 1.15.2 both the {@link IOWorker} and
 * {@link RegionFileStorage} constructors are package-private, so this shim lives in the vanilla package to reach them.
 * It is safe here because the test tree runs against the Mojang-mapped dev classpath and is never remapped; production
 * opens the storage reflectively in {@code WorldPathsImpl} instead, because a same-package shim throws
 * IllegalAccessError once a loader remaps the vanilla classes into a different runtime package.
 */
public class WdlRegionStorage extends IOWorker {
    public WdlRegionStorage(File directory, String name) {
        super(new RegionFileStorage(directory), name);
    }
}
