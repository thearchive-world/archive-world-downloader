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

final class EntityBuffer {
    private final Map<UUID, ChunkPos> chunkByUuid = new HashMap<>();

    /**
     * The buckets partition {@link #chunkByUuid} exactly: an entity in no bucket is never written and never counted as
     * a drop, so it would leave a silently short save that reports itself clean.
     */
    private final Map<ChunkPos, Map<UUID, CompoundTag>> byChunk = new LinkedHashMap<>();

    void accumulate(UUID uuid, ChunkPos pos, CompoundTag tag) {
        ChunkPos prior = chunkByUuid.put(uuid, pos);
        if (prior != null && !prior.equals(pos)) {
            byChunk.computeIfPresent(prior, (chunk, vacated) -> {
                vacated.remove(uuid);
                return vacated.isEmpty() ? null : vacated;
            });
        }
        byChunk.computeIfAbsent(pos, chunk -> new LinkedHashMap<>()).put(uuid, tag);
    }

    @Nullable
    ChunkPos chunkOf(UUID uuid) {
        return chunkByUuid.get(uuid);
    }

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
