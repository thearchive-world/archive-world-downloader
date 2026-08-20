// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-band chunk serialization axis, split into two steps: capture a live client {@link LevelChunk} into an immutable
 * {@link ChunkSnapshotSource}, then encode that snapshot into the vanilla region-file NBT (the minimal
 * {@code SerializableChunkData} slice, client-safe).
 *
 * <p>The split is what lets the heavy serialize run off the render thread: {@link #capture} runs on the client main
 * thread (the live read must), and {@link #encode} runs later on the save writer thread over the detached snapshot. At
 * 1.15.2 the chunk biomes come from the static biome registry (pre-1.16 serialize needs no client registries), so
 * neither step carries one. The capture step is client/level-coupled (it reads the light engine); the encode step is
 * pure and is what the headless round-trip exercises.
 */
public interface ChunkCodec {
    /**
     * Snapshot {@code chunk} (with its level) into an immutable {@link ChunkSnapshotSource} on the main thread,
     * detached so it may cross to the save writer thread for {@link #encode}.
     */
    ChunkSnapshotSource capture(LevelChunk chunk);

    /**
     * Encode an already-captured {@link ChunkSnapshotSource} to the vanilla region-file NBT (the tested slice). When
     * {@code synthesizeBlending} is set the chunk carries a synthesized {@code blending_data} marker so a freshly
     * generated neighbor blends against it instead of walling; the caller decides that per the target dimension and
     * generator (see {@code VanillaDimensions.synthesizeBlending}).
     */
    CompoundTag encode(ChunkSnapshotSource snapshot, boolean synthesizeBlending);
}
