// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe: every method holds the instance lock and {@code snapshot} returns a detached copy.
 */
public final class SavedChunkIndex {
    private static final long[] EMPTY = new long[0];

    private final Map<String, LongOpenHashSet> byDimension = new HashMap<>();

    private long version;

    public synchronized void add(String dimensionId, long chunkPos) {
        version++;
        byDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet()).add(chunkPos);
    }

    public synchronized void addAll(String dimensionId, long[] chunkPositions) {
        version++;
        if (chunkPositions.length == 0) {
            return;
        }
        LongOpenHashSet set = byDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet());
        for (long chunkPos : chunkPositions) {
            set.add(chunkPos);
        }
    }

    public synchronized long[] snapshot(String dimensionId) {
        LongOpenHashSet set = byDimension.get(dimensionId);
        return set == null ? EMPTY : set.toLongArray();
    }

    public synchronized void clear() {
        version++;
        byDimension.clear();
    }

    /**
     * Monotonic, bumped as the first statement of every coverage mutator (before any early return).
     */
    public synchronized long version() {
        return version;
    }
}
