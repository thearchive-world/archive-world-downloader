// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package net.minecraft.world.level.chunk.storage;

import java.io.File;

public class WdlRegionStorage extends IOWorker {
    public WdlRegionStorage(File directory, String name) {
        super(new RegionFileStorage(directory), name);
    }
}
