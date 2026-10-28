// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import org.jspecify.annotations.Nullable;

public interface EntitySink {
    /**
     * Capture {@code entities} (the live client entities sharing one chunk) into the chunk's entity NBT: keep only
     * those that pass the band's save gate (drops a passenger's standalone entry, removed entities, and vehicles
     * carrying exactly one player) and serialize each, then delegate to {@link #encodeChunk(List, ChunkPos)}. Returns
     * {@code null} when nothing is saveable.
     */
    @Nullable
    NBTTagCompound encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence);

    /**
     * Build the {@code Entities} carrier from already-serialized entity tags: the list verbatim under {@code Entities}
     * and nothing else.
     */
    @Nullable
    NBTTagCompound encodeChunk(List<NBTTagCompound> entityTags, ChunkPos pos);

    /**
     * Serialize a seated player's root vehicle to its standalone NBT for the player tag's {@code "RootVehicle"} record.
     * Bypasses the save gate {@link #encodeChunk(List, ChunkPos, boolean)} applies, because a vehicle carrying exactly
     * one player fails that gate by design.
     *
     * <p>Returns null when the vehicle's own save is refused. A throw while saving the vehicle or a passenger surfaces
     * as a {@code ReportedException}, which the caller must isolate.
     */
    @Nullable
    NBTTagCompound captureRootVehicle(Entity vehicle, boolean forceMobPersistence);
}
