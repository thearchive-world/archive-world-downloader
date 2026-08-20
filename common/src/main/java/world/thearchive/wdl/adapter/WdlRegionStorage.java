// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.jspecify.annotations.Nullable;

/**
 * The synchronous region store at this band: there is no {@code IOWorker} and no {@code RegionFileStorage} base, so
 * this owns the region-file cache directly the way vanilla {@code RegionFileCache} does, one {@link RegionFile} per
 * region coordinate opened on demand and reused. It is deliberately the raw NBT layer (no data fixing, matching the
 * higher band's raw {@code RegionFileStorage.read}/{@code write}): a chunk tag is written through the region file
 * exactly as given and read back verbatim, so what reaches disk is the anvil format a vanilla client of this band
 * reads.
 */
public class WdlRegionStorage implements AutoCloseable {
    private final File directory;
    private final Long2ObjectMap<RegionFile> regionCache = new Long2ObjectOpenHashMap<>();

    public WdlRegionStorage(File directory) {
        this.directory = directory;
    }

    private RegionFile regionFileFor(ChunkPos pos) {
        int regionX = pos.x >> 5;
        int regionZ = pos.z >> 5;
        long key = ChunkPos.asLong(regionX, regionZ);
        RegionFile cached = regionCache.get(key);
        if (cached != null) {
            return cached;
        }
        RegionFile regionFile = new RegionFile(new File(directory, "r." + regionX + "." + regionZ + ".mca"));
        regionCache.put(key, regionFile);
        return regionFile;
    }

    public @Nullable CompoundTag read(ChunkPos pos) throws IOException {
        try (DataInputStream input = regionFileFor(pos).method_3957(pos.x & 31, pos.z & 31)) {
            return input == null ? null : NbtIo.read(input);
        }
    }

    public void write(ChunkPos pos, CompoundTag tag) throws IOException {
        try (DataOutputStream output = regionFileFor(pos).method_3961(pos.x & 31, pos.z & 31)) {
            NbtIo.write(tag, output);
        }
    }

    @Override
    public void close() throws IOException {
        for (RegionFile regionFile : regionCache.values()) {
            regionFile.close();
        }
        regionCache.clear();
    }
}
