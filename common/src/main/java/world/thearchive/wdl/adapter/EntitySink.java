// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Per-band entity-capture axis: serialize the live client's entities of one chunk into the vanilla entity save NBT.
 *
 * <p>A three-member seam: two chunk steps mirroring {@link ChunkCodec} (the live
 * {@link #encodeChunk(List, ChunkPos, boolean)} and the pure {@link #encodeChunk(List, ChunkPos)}), plus
 * {@link #captureRootVehicle(Entity, boolean)}, a single-live gate-bypassing serialize for a seated player's mount. The
 * live step ({@link #encodeChunk(List, ChunkPos, boolean)}) serializes each saveable {@link Entity} via
 * {@code entity.save(CompoundTag)}; it is client-coupled. The pure step ({@link #encodeChunk(List, ChunkPos)}) builds
 * the {@code {Entities, Position, DataVersion}} envelope from already-serialized entity tags. A serialized-entity
 * {@link CompoundTag} is the entity analog of {@link ChunkSnapshotSource}, so the pure step takes a
 * {@code List<CompoundTag>}.
 *
 * <p>At 1.15.2 the entity save is {@code entity.save(CompoundTag)}; pre-1.16 serialize needs no client registries.
 */
public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the entities-region NBT: keep only
     * those that pass the band's save gate (drops a passenger's standalone entry, removed entities, and player-only
     * vehicles) and serialize each via {@code entity.save}, whose recursion nests a vehicle's passengers under it, so
     * each entity is written once, then delegate to {@link #encodeChunk(List, ChunkPos)}. Returns {@code null} when
     * nothing is saveable, so the empty entity-chunk is skipped rather than written.
     *
     * <p>A captured named mob always gets {@code PersistenceRequired} restored (the client never receives the
     * server-authoritative flag, so it would despawn on open), as does a mob whose equipment proves a loot pickup (an
     * item impossible for its natural spawn); when {@code forceMobPersistence} is set, every captured mob gets it,
     * named or not.
     */
    @Nullable
    CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence);

    /**
     * Build the {@code {Entities, Position, DataVersion}} entities-region envelope from already-serialized entity tags
     * (the tested slice): {@code Entities} is the list verbatim, {@code Position} encodes {@code pos}, and
     * {@code DataVersion} is the current data version.
     */
    @Nullable
    CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record.
     * Bypasses the save gate {@link #encodeChunk(List, ChunkPos, boolean)} applies, because a vehicle carrying exactly
     * one player fails that gate by design. Leash-safe over the vehicle and its passengers; the player passenger
     * self-excludes ({@code saveAsPassenger} refuses a type with no encode id). A mount dismounted in the downloaded
     * world despawns like any other captured mob, so it takes the same {@code PersistenceRequired} restoration the
     * entities path applies: a named mob and a loot-equipped mob are stamped unconditionally (the client entity never
     * carries the server flag, so it would despawn on open), and with {@code forceMobPersistence} set every captured
     * mob is.
     *
     * <p>Returns null when the vehicle's own save is refused ({@code save()==false}: a removed entity or a null encode
     * id). It does not swallow a codec reject: a modded or rolled-back vehicle, or a non-player passenger, whose state
     * the save codec rejects throws a {@code ReportedException} out of {@code entity.save}, which the caller must
     * isolate. Client main thread only (it reads a live entity); never from a background/overlay read path.
     */
    @Nullable
    CompoundTag captureRootVehicle(Entity vehicle, boolean forceMobPersistence);
}
