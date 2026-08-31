// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Per-band entity-capture axis: serialize the live client's entities of one chunk into the vanilla entity save NBT.
 *
 * <p>A three-member seam: two chunk steps mirroring {@link ChunkCodec} (the live
 * {@link #encodeChunk(List, ChunkPos, boolean)} and the pure {@link #encodeChunk(List, ChunkPos)}), plus
 * {@link #captureRootVehicle(Entity, boolean)}, a single-live gate-bypassing serialize for a seated player's mount. The
 * live step ({@link #encodeChunk(List, ChunkPos, boolean)}) serializes each saveable {@link Entity}; it is
 * client-coupled. The pure step ({@link #encodeChunk(List, ChunkPos)}) builds the
 * {@code {Entities, Position, DataVersion}} envelope from already-serialized entity tags. A serialized-entity
 * {@code NBTTagCompound} is the entity analog of {@link ChunkSnapshotSource}, so the pure step takes a
 * {@code List<NBTTagCompound>}.
 *
 * <p>Pre-1.16 serialize needs no client registries.
 */
public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the entities-region NBT: keep only
     * those that pass the band's save gate (drops a passenger's standalone entry, removed entities, and player-only
     * vehicles) and serialize each, then delegate to {@link #encodeChunk(List, ChunkPos)}. Returns {@code null} when
     * nothing is saveable, so the empty entity-chunk is skipped rather than written.
     *
     * <p>A captured named mob always gets {@code PersistenceRequired} restored (the client never receives the
     * server-authoritative flag, so it would despawn on open), as does a mob whose equipment proves a loot pickup (an
     * item impossible for its natural spawn); when {@code forceMobPersistence} is set, every captured mob gets it,
     * named or not.
     */
    @Nullable
    NBTTagCompound encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence);

    /**
     * Build the {@code {Entities}} entities-region envelope from already-serialized entity tags (the tested slice):
     * {@code Entities} is the list verbatim.
     */
    @Nullable
    NBTTagCompound encodeChunk(List<NBTTagCompound> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record.
     * Bypasses the save gate {@link #encodeChunk(List, ChunkPos, boolean)} applies, because a vehicle carrying exactly
     * one player fails that gate by design. Leash-safe over the vehicle and its passengers; the player passenger
     * self-excludes. A mount dismounted in the downloaded world despawns like any other captured mob, so it takes the
     * same {@code PersistenceRequired} restoration the entities path applies: a named mob and a loot-equipped mob are
     * stamped unconditionally (the client entity never carries the server flag, so it would despawn on open), and with
     * {@code forceMobPersistence} set every captured mob is.
     *
     * <p>Returns null when the vehicle's own save is refused. It does not swallow a codec reject: a modded or
     * rolled-back vehicle, or a non-player passenger, whose state the save codec rejects throws a
     * {@code ReportedException}, which the caller must isolate. Client main thread only (it reads a live entity); never
     * from a background/overlay read path.
     */
    @Nullable
    NBTTagCompound captureRootVehicle(Entity vehicle, boolean forceMobPersistence);
}
