// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the entities-region NBT: keep only
     * those that pass the band's save gate (drops a passenger's standalone entry, removed entities, and vehicles
     * carrying exactly one player) and serialize each via {@code entity.save}, whose recursion nests a vehicle's
     * passengers under it, so each entity is written once, then delegate to {@link #encodeChunk(List, ChunkPos)}.
     * Returns {@code null} when nothing is saveable.
     */
    @Nullable
    CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, RegistryAccess registries,
            boolean forceMobPersistence);

    /**
     * Build the {@code {Entities, Position, DataVersion}} entities-region envelope from already-serialized entity tags:
     * {@code Entities} is the list verbatim, {@code Position} encodes {@code pos}, and {@code DataVersion} is the
     * current data version.
     */
    @Nullable
    CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record.
     * Bypasses the save gate {@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)} applies, because a vehicle
     * carrying exactly one player fails that gate by design.
     *
     * <p>Returns null when the vehicle's own save is refused. A throw while saving the vehicle or a passenger surfaces
     * from {@code entity.save} as a {@code ReportedException}, which the caller must isolate.
     */
    @Nullable
    CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess registries, boolean forceMobPersistence);
}
