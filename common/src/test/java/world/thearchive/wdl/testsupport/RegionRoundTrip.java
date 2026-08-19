// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * Drives a chunk NBT tag through vanilla's real region pipeline ({@link IOWorker}, the async region-file worker below
 * 1.20.6) and back, so the codec round-trip exercises on-disk Anvil write/read rather than an in-memory shortcut.
 */
public final class RegionRoundTrip {
    private RegionRoundTrip() {}

    /** Open a region storage rooted at {@code directory} (caller closes it). {@code IOWorker}'s ctor is protected. */
    public static IOWorker open(Path directory) {
        return new TestRegionStorage(directory, false, "chunk");
    }

    /**
     * Write {@code tag} at {@code pos}, drain to disk, then read it back through a <em>fresh</em> storage instance: a
     * pass then proves the bytes survived a real serialize -> disk -> deserialize.
     */
    public static CompoundTag writeThenRead(Path directory, ChunkPos pos, CompoundTag tag) {
        try (IOWorker writer = open(directory)) {
            writer.store(pos, tag).join();
            writer.synchronize().join();
        } catch (IOException e) {
            throw new RuntimeException("failed writing region chunk " + pos, e);
        }
        try (IOWorker reader = open(directory)) {
            return Optional.ofNullable(reader.load(pos))
                    .orElseThrow(() -> new IllegalStateException("no chunk read back at " + pos));
        } catch (IOException e) {
            throw new RuntimeException("failed reading region chunk " + pos, e);
        }
    }

    private static final class TestRegionStorage extends IOWorker {
        private TestRegionStorage(Path directory, boolean sync, String name) {
            super(directory.toFile(), sync, name);
        }
    }
}
