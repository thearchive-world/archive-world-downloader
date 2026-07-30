// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Saved chunk positions per dimension, for the coverage overlay: the recovered on-disk priors seeded on a resume plus
 * the positions written live this session. Keyed by the live client dimension id string (e.g.
 * {@code minecraft:overworld}, or a Multiverse custom id like {@code minecraft:worlds/2b2t/2b2t_1}) so it matches what
 * the overlay providers query the overlay under, unlike the disk-routing set which is keyed by the mapped vanilla type.
 * MC-free (a String partition over fastutil long sets) and headless-testable. Thread-safe: the capture tick and the
 * resume seed write via {@link #add} and {@link #addAll}, an overlay provider's async draw loop reads via
 * {@link #snapshot}, so every method holds the instance lock and {@code
 * snapshot} returns a detached copy.
 */
public final class SavedChunkIndex {
    private static final long[] EMPTY = new long[0];

    private final Map<String, LongOpenHashSet> byDimension = new HashMap<>();

    private long version;

    /** Record a saved chunk position under its live dimension id. */
    public synchronized void add(String dimensionId, long chunkPos) {
        version++;
        byDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet()).add(chunkPos);
    }

    /** Record a batch of saved chunk positions under one live dimension id (the resume prior-coverage seed). */
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

    /** A detached copy of the saved chunk longs for a dimension (empty if none), safe to read off-thread. */
    public synchronized long[] snapshot(String dimensionId) {
        LongOpenHashSet set = byDimension.get(dimensionId);
        return set == null ? EMPTY : set.toLongArray();
    }

    /** Drop every dimension's set (called at capture start and stop, so the overlay is empty when idle). */
    public synchronized void clear() {
        version++;
        byDimension.clear();
    }

    /**
     * Monotonic, bumped as the first statement of every coverage mutator (before any early return), read by the
     * JourneyMap driver to skip an unchanged rebuild. A redundant bump on a no-op mutation is fine: it only costs one
     * wasted rebuild, and it keeps the counter strictly monotonic.
     */
    public synchronized long version() {
        return version;
    }
}
