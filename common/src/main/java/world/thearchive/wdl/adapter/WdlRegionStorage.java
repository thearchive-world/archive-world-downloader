// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.File;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/**
 * The synchronous region store at this band: there is no {@code IOWorker}, and vanilla {@code RegionFileStorage} is
 * abstract with a protected constructor and a protected {@code write}. A subclass reaches both across packages in a way
 * the loader remap preserves, so this is a shim rather than the reflective open a higher band's package-private
 * constructor forces.
 */
public class WdlRegionStorage extends RegionFileStorage {
    public WdlRegionStorage(File directory) {
        super(directory);
    }

    @Override
    public void write(ChunkPos pos, CompoundTag tag) throws IOException {
        super.write(pos, tag);
    }
}
