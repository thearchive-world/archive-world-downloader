// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;

public interface ChunkCodec {
    /**
     * Snapshot {@code chunk} (with its level) into an immutable {@link ChunkSnapshotSource} on the main thread,
     * detached so it may cross to the save writer thread for {@link #encode}.
     */
    ChunkSnapshotSource capture(LevelChunk chunk, RegistryAccess registries);

    /**
     * Encode an already-captured {@link ChunkSnapshotSource} to the vanilla region-file NBT. When
     * {@code synthesizeBlending} is set, on a version whose world generation blends old chunks, the chunk carries a
     * synthesized {@code blending_data} marker so a freshly generated neighbor blends against it instead of walling; on
     * other versions the encode ignores the flag.
     */
    CompoundTag encode(ChunkSnapshotSource snapshot, RegistryAccess registries, boolean synthesizeBlending);
}
