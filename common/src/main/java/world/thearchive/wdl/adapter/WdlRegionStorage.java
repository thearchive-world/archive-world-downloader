// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.File;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

public class WdlRegionStorage extends RegionFileStorage {
    public WdlRegionStorage(File directory) {
        super(directory);
    }

    @Override
    public void write(ChunkPos pos, CompoundTag tag) throws IOException {
        super.write(pos, tag);
    }
}
