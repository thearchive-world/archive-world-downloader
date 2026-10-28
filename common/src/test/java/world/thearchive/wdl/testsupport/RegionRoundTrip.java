// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

public final class RegionRoundTrip {
    private RegionRoundTrip() {}

    public static RegionStorageInfo overworldChunkInfo() {
        return new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk");
    }

    public static SimpleRegionStorage open(Path directory) {
        return new SimpleRegionStorage(
                overworldChunkInfo(), directory, DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    /**
     * Write {@code tag} at {@code pos}, drain to disk, then read it back through a <em>fresh</em> storage instance.
     */
    public static CompoundTag writeThenRead(Path directory, ChunkPos pos, CompoundTag tag) {
        try (SimpleRegionStorage writer = open(directory)) {
            writer.write(pos, tag).join();
            writer.synchronize(true).join();
        } catch (IOException e) {
            throw new RuntimeException("failed writing region chunk " + pos, e);
        }
        try (SimpleRegionStorage reader = open(directory)) {
            return reader.read(pos).join()
                    .orElseThrow(() -> new IllegalStateException("no chunk read back at " + pos));
        } catch (IOException e) {
            throw new RuntimeException("failed reading region chunk " + pos, e);
        }
    }
}
