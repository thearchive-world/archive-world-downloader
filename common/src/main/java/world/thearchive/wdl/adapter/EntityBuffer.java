// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * The per-tick entity accumulation buffer: the entity analog of the keep-hot chunk buffer. Entities are encoded the
 * moment they are seen near the player and held by UUID, so one that unloads mid-roam (the client loads entities only
 * within the server's tracking range, narrower than render distance) is still captured rather than lost at finish.
 * Keyed by UUID, so re-seeing an entity in a new chunk re-homes it and drops the stale copy (the write-time dedup,
 * within-session); chunks drain independently, so the buffer stays bounded as the player roams.
 *
 * <p>Entities are bucketed by chunk, so listing the buffered chunks and draining one both cost their own result rather
 * than a walk of every buffered entity: the flush poll runs every tick against a buffer that a wall of item frames near
 * the player can push into five figures.
 *
 * <p>Holds already-serialized entity tags (the snapshot-on-main discipline: each entity is encoded on the main thread
 * when seen, and only finished tags are later handed across the writer queue). MC-typed only in
 * {@code ChunkPos}/{@code CompoundTag}; the accumulation and dedup are plain map operations.
 */
final class EntityBuffer {
    private final Map<UUID, ChunkPos> chunkByUuid = new HashMap<>();

    /**
     * One bucket per chunk holding at least one entity, each bucket in the order its entities were seen. The buckets
     * partition {@link #chunkByUuid} exactly, and a bucket emptied by a re-home is removed rather than left behind: an
     * entity in no bucket is never written and never counted as a drop, so it would leave a silently short save that
     * reports itself clean.
     */
    private final Map<ChunkPos, Map<UUID, CompoundTag>> byChunk = new LinkedHashMap<>();

    /**
     * Accumulate (or re-home) an entity's encoded tag, keyed by UUID. The latest chunk wins: if the entity was buffered
     * in another chunk, that copy is implicitly dropped (this entry replaces it), the write-time dedup.
     */
    void accumulate(UUID uuid, ChunkPos pos, CompoundTag tag) {
        ChunkPos prior = chunkByUuid.put(uuid, pos);
        if (prior != null && !prior.equals(pos)) {
            byChunk.computeIfPresent(prior, (chunk, vacated) -> {
                vacated.remove(uuid);
                return vacated.isEmpty() ? null : vacated; // a null remapping drops the emptied bucket
            });
        }
        byChunk.computeIfAbsent(pos, chunk -> new LinkedHashMap<>()).put(uuid, tag);
    }

    /** The chunk an entity is currently buffered in, or null if it is not buffered (the new-or-moved check). */
    @Nullable
    ChunkPos chunkOf(UUID uuid) {
        return chunkByUuid.get(uuid);
    }

    /** Drain and return the encoded tags for the entities in {@code pos}'s chunk, removing them from the buffer. */
    List<CompoundTag> drainChunk(ChunkPos pos) {
        Map<UUID, CompoundTag> bucket = byChunk.remove(pos);
        if (bucket == null) {
            return new ArrayList<>();
        }
        for (UUID uuid : bucket.keySet()) {
            chunkByUuid.remove(uuid);
        }
        return new ArrayList<>(bucket.values()); // fresh and mutable: the caller filters the drain in place
    }

    /** Every chunk currently holding at least one buffered entity (a snapshot, safe to iterate while draining). */
    Set<ChunkPos> bufferedChunks() {
        return new LinkedHashSet<>(byChunk.keySet());
    }

    boolean isEmpty() {
        return byChunk.isEmpty();
    }
}
