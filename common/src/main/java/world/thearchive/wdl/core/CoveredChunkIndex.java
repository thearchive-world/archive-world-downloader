// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Covered chunk positions per dimension, for the two-tone coverage overlay: the chunks taken to have had their static
 * decorations (item frames, glow item frames, paintings, armor stands) sent and captured. The overlay draws a saved
 * chunk in the user's covered hue when it is covered here and in their suspect hue when it is not. A direct parallel of
 * {@link SavedChunkIndex}: keyed by the live client dimension id string so it matches what the overlay providers query
 * the overlay under. MC-free (a String partition over fastutil long collections) and headless-testable. Thread-safe:
 * the capture tick writes via {@link #addDisc} and the resume seed via {@link #addAll}, an overlay provider's async
 * draw loop reads via {@link #snapshot}, so every method holds the instance lock and {@code
 * snapshot} returns a detached copy that is safe to hand off-thread.
 *
 * <p>Coverage is stored in two per-dimension sets, so a shrinking send range can be honored. The seed set holds the
 * resume prior-coverage ({@link #addAll}), which is not reconstructible from the trail and must survive any recompute.
 * The trail-derived set holds the swept discs ({@link #addDisc} and {@link #recompute}), which a recompute clears and
 * rebuilds from the trail. A {@link #snapshot} is the union of both.
 *
 * <p>Each trail center also carries the view-distance cap in force when it was walked ({@link #recordTrail}), so a
 * dip-then-rise in view distance cannot repaint history: a recompute paints a center at the lesser of the current
 * radius and its own recorded cap, never wider than what that stretch was actually walked at.
 */
public final class CoveredChunkIndex {
    private static final long[] EMPTY = new long[0];

    private final Map<String, LongOpenHashSet> seedByDimension = new HashMap<>();
    private final Map<String, LongOpenHashSet> trailCoveredByDimension = new HashMap<>();
    private final Map<String, LongArrayList> trailByDimension = new HashMap<>();
    private final Map<String, Long2IntOpenHashMap> trailCapByDimension = new HashMap<>();

    private long version;

    /** Record a batch of covered chunk positions under one live dimension id (the resume prior-coverage seed). */
    public synchronized void addAll(String dimensionId, long[] chunkPositions) {
        version++;
        if (chunkPositions.length == 0) {
            return;
        }
        LongOpenHashSet set = seedByDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet());
        for (long chunkPos : chunkPositions) {
            set.add(chunkPos);
        }
    }

    /**
     * Record the coverage disc around a recording-path chunk under its live dimension id: every chunk whose center is
     * within {@code radius} chunks (Euclidean) of ({@code centerX}, {@code centerZ}). This replays the vanilla
     * decoration send rule at chunk granularity, where the caller passes {@code radius} from the measured send range
     * ({@link SendRangeEstimator#radiusChunks}): the static decoration types are sent once on entry within that range.
     * Writes the trail-derived set, so a later {@link #recompute} at a smaller radius supersedes it.
     */
    public synchronized void addDisc(String dimensionId, int centerX, int centerZ, int radius) {
        version++;
        addDiscInto(trailCoveredByDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet()),
                centerX, centerZ, radius);
    }

    /**
     * Append a visited path center to the dimension's trail, deduped, with the view-distance cap in force when it was
     * walked. A re-cross of an already-recorded center does not re-append; instead the stored cap is replaced with the
     * greater of the stored and the new cap, so a stretch later re-walked at a higher view distance can paint wider,
     * but a dip after a high-cap walk cannot shrink what was already recorded.
     */
    public synchronized void recordTrail(String dimensionId, int centerX, int centerZ, int capChunks) {
        long key = RegionMath.chunkAsLong(centerX, centerZ);
        Long2IntOpenHashMap caps = trailCapByDimension.computeIfAbsent(dimensionId,
                dimension -> new Long2IntOpenHashMap());
        if (caps.containsKey(key)) {
            caps.put(key, Math.max(caps.get(key), capChunks));
        } else {
            caps.put(key, capChunks);
            trailByDimension.computeIfAbsent(dimensionId, dimension -> new LongArrayList()).add(key);
        }
    }

    /**
     * Clear the dimension's trail-derived coverage and rebuild it as the union of discs over its whole recorded trail.
     * Each center paints at the lesser of two shrink drivers: the read-time clamp ({@code radius}, the range in force
     * at this call) and the center's own recorded cap ({@link #recordTrail}, the view distance in force when that
     * center was walked). Used on any measured-range change: a growth flips path-swept chunks from entity-suspect to
     * covered, and either driver shrinking contracts the boundary the earlier, larger discs drew. Clear-and-rebuild is
     * replace semantics, correct in both directions. The resume seed lives in a separate set and is untouched, so it
     * survives every recompute.
     */
    public synchronized void recompute(String dimensionId, int radius) {
        version++;
        LongArrayList trail = trailByDimension.get(dimensionId);
        if (trail == null) {
            return;
        }
        Long2IntOpenHashMap caps = trailCapByDimension.get(dimensionId);
        LongOpenHashSet set = trailCoveredByDimension.computeIfAbsent(dimensionId, key -> new LongOpenHashSet());
        set.clear();
        for (int i = 0; i < trail.size(); i++) {
            long center = trail.getLong(i);
            int capChunks = caps == null ? radius : caps.get(center);
            addDiscInto(set, RegionMath.chunkX(center), RegionMath.chunkZ(center), Math.min(radius, capChunks));
        }
    }

    private static void addDiscInto(LongOpenHashSet set, int centerX, int centerZ, int radius) {
        int radiusSquared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radiusSquared) {
                    set.add(RegionMath.chunkAsLong(centerX + dx, centerZ + dz));
                }
            }
        }
    }

    /**
     * A detached copy of the covered chunk longs for a dimension (empty if none), the union of the resume seed and the
     * trail-derived coverage, safe to read off-thread.
     */
    public synchronized long[] snapshot(String dimensionId) {
        LongOpenHashSet seed = seedByDimension.get(dimensionId);
        LongOpenHashSet trailCovered = trailCoveredByDimension.get(dimensionId);
        if (seed == null) {
            return trailCovered == null ? EMPTY : trailCovered.toLongArray();
        }
        if (trailCovered == null) {
            return seed.toLongArray();
        }
        LongOpenHashSet union = new LongOpenHashSet(seed);
        union.addAll(trailCovered);
        return union.toLongArray();
    }

    /**
     * Drop every dimension's coverage and trail (called at capture start and stop, so the overlay has no covered tone
     * when idle and a later recompute cannot resurrect a previous session's path).
     */
    public synchronized void clear() {
        version++;
        seedByDimension.clear();
        trailCoveredByDimension.clear();
        trailByDimension.clear();
        trailCapByDimension.clear();
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
