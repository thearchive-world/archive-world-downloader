// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;

/**
 * Per-band chunk serialization axis, split into two steps: capture a live client {@link Chunk} into an immutable
 * {@link ChunkSnapshotSource}, then encode that snapshot into the vanilla region-file NBT (the minimal slice of
 * vanilla's chunk write, client-safe).
 *
 * <p>The split is what lets the heavy serialize run off the render thread: {@link #capture} runs on the client main
 * thread (the live read must), and {@link #encode} runs later on the save writer thread over the detached snapshot. The
 * chunk biomes come from the static biome registry (pre-1.16 serialize needs no client registries), so neither step
 * carries one. The capture step is client/level-coupled (it reads the live chunk and its level); the encode step is
 * pure and is what the headless round-trip exercises.
 */
public interface ChunkCodec {
    /**
     * Snapshot {@code chunk} (with its world) into an immutable {@link ChunkSnapshotSource} on the main thread,
     * detached so it may cross to the save writer thread for {@link #encode}.
     */
    ChunkSnapshotSource capture(Chunk chunk);

    /**
     * Encode an already-captured {@link ChunkSnapshotSource} to the vanilla region-file NBT (the tested slice). When
     * {@code synthesizeBlending} is set, on a version whose world generation blends old chunks, the chunk carries a
     * synthesized {@code blending_data} marker so a freshly generated neighbor blends against it instead of walling; on
     * other versions the encode ignores the flag. The caller sets it per the target dimension and generator (see
     * {@code VanillaDimensions.shouldSynthesizeBlending}).
     */
    NBTTagCompound encode(ChunkSnapshotSource snapshot, boolean synthesizeBlending);
}
