// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.adapter.WdlRegionStorage;

/**
 * Drives a chunk NBT tag through vanilla's real region pipeline (the synchronous {@link WdlRegionStorage} over
 * RegionFileStorage at this band) and back, so the codec round-trip exercises on-disk Anvil write/read rather than an
 * in-memory shortcut.
 */
public final class RegionRoundTrip {
    private RegionRoundTrip() {}

    /** Open a region storage rooted at {@code directory} (caller closes it). */
    public static WdlRegionStorage open(Path directory) {
        return new WdlRegionStorage(directory.toFile());
    }

    /**
     * Write {@code tag} at {@code pos}, drain to disk, then read it back through a <em>fresh</em> storage instance: a
     * pass then proves the bytes survived a real serialize -> disk -> deserialize.
     */
    public static CompoundTag writeThenRead(Path directory, ChunkPos pos, CompoundTag tag) {
        try (WdlRegionStorage writer = open(directory)) {
            writer.write(pos, tag);
        } catch (IOException e) {
            throw new RuntimeException("failed writing region chunk " + pos, e);
        }
        try (WdlRegionStorage reader = open(directory)) {
            return Optional.ofNullable(reader.read(pos))
                    .orElseThrow(() -> new IllegalStateException("no chunk read back at " + pos));
        } catch (IOException e) {
            throw new RuntimeException("failed reading region chunk " + pos, e);
        }
    }
}
