// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Per-band entity-capture axis: serialize the live client's entities of one chunk into the vanilla {@code entities/}
 * region NBT: a client-safe lift of {@code EntityStorage.storeEntities}'s write branch.
 *
 * <p>A three-member seam: two chunk steps mirroring {@link ChunkCodec} (the live
 * {@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)} and the pure {@link #encodeChunk(List, ChunkPos)}),
 * plus {@link #captureRootVehicle(Entity, RegistryAccess, boolean)}, a single-live gate-bypassing serialize for a
 * seated player's mount. The live step ({@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)}) serializes each
 * saveable {@link Entity} via {@code entity.save(ValueOutput)}; it is client-coupled. The pure step
 * ({@link #encodeChunk(List, ChunkPos)}) builds the {@code {Entities, Position, DataVersion}} envelope from
 * already-serialized entity tags and is what the headless round-trip exercises. A serialized-entity {@link CompoundTag}
 * is the entity analog of {@link ChunkSnapshotSource}, so the pure step takes a {@code List<CompoundTag>} and needs no
 * {@link RegistryAccess} ({@code ChunkPos.CODEC} is registry-independent).
 *
 * <p>Both shapes are band-agnostic so the 1.21.11 ({@code entity.save(ValueOutput)} + {@code
 * TagValueOutput}) and the deferred &le;1.21.8 sub-bands can satisfy them; the impls differ only internally (the
 * per-band entity-save API).
 */
public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the entities-region NBT: keep only
     * those that {@link Entity#shouldBeSaved()} (drops a passenger's standalone entry, removed entities, and
     * player-only vehicles) and serialize each via {@code entity.save(ValueOutput)}, whose recursion nests a vehicle's
     * passengers under it, so each entity is written once, then delegate to {@link #encodeChunk(List, ChunkPos)}.
     * Returns {@code null} when nothing is saveable, so the empty entity-chunk is skipped rather than written.
     *
     * <p>A captured named mob always gets {@code PersistenceRequired} restored (the client never receives the
     * server-authoritative flag, so it would despawn on open), as does a mob whose equipment proves a loot pickup (an
     * item impossible for its natural spawn); when {@code forceMobPersistence} is set, every captured mob gets it,
     * named or not.
     */
    @Nullable
    CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, RegistryAccess registries,
            boolean forceMobPersistence);

    /**
     * Build the {@code {Entities, Position, DataVersion}} entities-region envelope from already-serialized entity tags
     * (the tested slice): {@code Entities} is the list verbatim, {@code Position} encodes {@code pos} via
     * {@code ChunkPos.CODEC}, and {@code DataVersion} is the current data version. Returns {@code null} for an empty
     * list (skip the chunk).
     */
    @Nullable
    CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record,
     * the way {@code ServerPlayer.saveParentVehicle} does with {@code root.save}. Bypasses the
     * {@link Entity#shouldBeSaved()} gate {@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)} applies,
     * because a vehicle carrying exactly one player fails that gate by design. Leash-safe over the vehicle and its
     * passengers; the player passenger self-excludes ({@code saveAsPassenger} refuses a type with no encode id). A
     * mount dismounted in the downloaded world despawns like any other captured mob, so it takes the same
     * {@code PersistenceRequired} restoration the entities path applies: a named mob and a loot-equipped mob are
     * stamped unconditionally (the client entity never carries the server flag, so it would despawn on open), and with
     * {@code forceMobPersistence} set every captured mob is.
     *
     * <p>Returns null when the vehicle's own save is refused ({@code save()==false}: a removed entity or a null encode
     * id). It does not swallow a codec reject: a modded or rolled-back vehicle, or a non-player passenger, whose state
     * the save codec rejects throws a {@code ReportedException} out of {@code entity.save}, which the caller must
     * isolate. Client main thread only (it reads a live entity); never from a background/overlay read path.
     */
    @Nullable
    CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess registries, boolean forceMobPersistence);
}
