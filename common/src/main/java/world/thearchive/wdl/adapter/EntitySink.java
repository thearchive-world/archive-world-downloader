// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the chunk's entity NBT: keep only
     * those that pass the band's save gate (drops a passenger's standalone entry and removed entities) and serialize
     * each via {@code entity.save}, whose recursion nests a vehicle's passengers under it, so each entity is written
     * once, then delegate to {@link #encodeChunk(List, ChunkPos)}. Returns {@code null} when nothing is saveable.
     */
    @Nullable
    CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence);

    /**
     * Build the {@code Entities} carrier from already-serialized entity tags: the list verbatim under {@code Entities}
     * and nothing else.
     */
    @Nullable
    CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record.
     *
     * <p>Returns null when the vehicle's own save is refused. A throw while saving the vehicle or a passenger surfaces
     * from {@code entity.save} as a {@code ReportedException}, which the caller must isolate.
     */
    @Nullable
    CompoundTag captureRootVehicle(Entity vehicle, boolean forceMobPersistence);
}
