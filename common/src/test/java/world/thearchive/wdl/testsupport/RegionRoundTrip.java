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

/**
 * Drives a chunk NBT tag through vanilla's real region pipeline ({@link SimpleRegionStorage}, which owns the async
 * {@code IOWorker}) and back, so the codec round-trip exercises on-disk Anvil write/read rather than an in-memory
 * shortcut.
 */
public final class RegionRoundTrip {
    private RegionRoundTrip() {}

    /** The {@code RegionStorageInfo} for overworld chunks; {@code level}/{@code type} are cosmetic. */
    public static RegionStorageInfo overworldChunkInfo() {
        return new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk");
    }

    /** Open a region storage rooted at {@code directory} (caller closes it). */
    public static SimpleRegionStorage open(Path directory) {
        return new SimpleRegionStorage(
                overworldChunkInfo(), directory, DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    /**
     * Write {@code tag} at {@code pos}, drain to disk, then read it back through a <em>fresh</em> storage instance: a
     * pass then proves the bytes survived a real serialize -> disk -> deserialize.
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
