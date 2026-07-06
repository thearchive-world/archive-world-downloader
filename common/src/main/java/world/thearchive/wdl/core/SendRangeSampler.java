// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The sample gatekeeper for the send-range estimator: the never-moved never-ridden position book, the
 * anomaly-suppression window, the two-flag camera hold, the 16-block haircut, and the start-window sweep generations. A
 * sample commits only for ids that never moved and never rode; a never-moved entry's registered position is current by
 * construction, which closes every stale-position hazard (boarding freezes, passenger-boosted vehicles, fork teleports,
 * drift) without position tracking or quarantine releases. Registration is never gated by the sampling gates, and no
 * registration path ever clears a bit.
 *
 * Thread contract: the book is a concurrent map with immutable entries, every mutation a whole-entry replace.
 * Cross-thread scalars are volatile or generation counters; see each field. The clock is injected so the unit tests own
 * time.
 */
public final class SendRangeSampler {
    public static final int NO_SAMPLE = -1;
    public static final int HAIRCUT_BLOCKS = 16;
    public static final double MOVED_EPSILON_BLOCKS = 1.0;
    public static final long WINDOW_NANOS = 3_000_000_000L;
    public static final double FAST_TICK_BLOCKS = 0.8;

    private static final class Entry {
        final boolean hasPosition;
        final double x;
        final double z;
        final boolean moved;
        final boolean ridden;

        Entry(boolean hasPosition, double x, double z, boolean moved, boolean ridden) {
            this.hasPosition = hasPosition;
            this.x = x;
            this.z = z;
            this.moved = moved;
            this.ridden = ridden;
        }
    }

    private final Map<Integer, Entry> book = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    private volatile long windowDeadline;
    private final AtomicInteger sweepArmGeneration = new AtomicInteger();
    private volatile int sweptGeneration;
    private final AtomicInteger cameraLatchGeneration = new AtomicInteger();
    private volatile int cameraConsumedGeneration;
    private volatile boolean cameraDetachedApplied;
    private boolean prevDetached;
    private int previousLatchGeneration;

    /**
     * The over-claim ceiling in blocks for a send-range sample: a distance beyond the render distance (floored at 2
     * chunks) cannot be a genuine server send range, so the sampling feeds discard it. One formula for every feed, so
     * the ceiling never drifts between the arrival, removal, and prime paths.
     */
    public static int plausibleMaxBlocks(int renderDistanceChunks) {
        return Math.max(renderDistanceChunks, 2) * 16;
    }

    public SendRangeSampler(LongSupplier nanos, boolean cameraDetachedAtStart) {
        this.nanos = nanos;
        this.cameraDetachedApplied = cameraDetachedAtStart;
        this.prevDetached = cameraDetachedAtStart;
        armWindow(); // capture start is itself an anomaly: a teleport landing just before start predates the sampler
    }

    /** Netty side: the id appeared anywhere in a SetPassengers packet; permanently feed-3-ineligible. */
    public void markRidden(int id) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(false, 0.0, 0.0, false, true)
                : new Entry(entry.hasPosition, entry.x, entry.z, entry.moved, true));
    }

    /** Netty side: a relative move; a nonzero delta marks the id moved, known or not; zero deltas are inert. */
    public void markMovedRelative(int id, boolean nonzeroDelta) {
        if (!nonzeroDelta) {
            return;
        }
        markMoved(id);
    }

    /**
     * Netty side: an absolute position, teleport, or sync. Compares against the registered anchor; within the epsilon
     * it is a vanilla same-position reminder and inert. An unknown id gets a bits-only moved entry (a teleport teed
     * before the prime registers would otherwise leave a stale anchor with clear bits); a known entry without a
     * position is conservatively marked moved.
     */
    public void markMovedAbsolute(int id, double x, double z) {
        book.compute(id, (key, entry) -> {
            if (entry == null) {
                return new Entry(false, 0.0, 0.0, true, false);
            }
            if (!entry.hasPosition) {
                return new Entry(false, 0.0, 0.0, true, entry.ridden);
            }
            double dx = x - entry.x;
            double dz = z - entry.z;
            if (dx * dx + dz * dz <= MOVED_EPSILON_BLOCKS * MOVED_EPSILON_BLOCKS) {
                return entry;
            }
            return new Entry(entry.hasPosition, entry.x, entry.z, true, entry.ridden);
        });
    }

    /** Netty side: a teleport carrying relative flags has no absolute to compare; treat as moved. */
    public void markMovedRelativeTeleport(int id) {
        markMoved(id);
    }

    private void markMoved(int id) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(false, 0.0, 0.0, true, false)
                : new Entry(entry.hasPosition, entry.x, entry.z, true, entry.ridden));
    }

    /** Netty side: a spawn is definitionally the fresh codec base, so it overwrites the anchor; bits survive. */
    public void registerArrival(int id, double x, double z) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(true, x, z, false, false)
                : new Entry(true, x, z, entry.moved, entry.ridden));
    }

    /** Main side: a prime registration fills a missing anchor only (putIfAbsent shape); bits survive. */
    public void registerSeed(int id, double x, double z) {
        book.compute(id, (key, entry) -> {
            if (entry == null) {
                return new Entry(true, x, z, false, false);
            }
            if (entry.hasPosition) {
                return entry;
            }
            return new Entry(true, x, z, entry.moved, entry.ridden);
        });
    }

    /** Netty side, feed 1: suppression-gated, no haircut (arrivals fire at or inside the edge). */
    public int arrivalSample(double playerX, double playerZ, double entityX, double entityZ) {
        if (suppressed()) {
            return NO_SAMPLE;
        }
        return distance(playerX, playerZ, entityX, entityZ);
    }

    /**
     * Netty side, feed 3: samples only a both-bits-clear anchored entry, haircut applied; the entry is dropped either
     * way (the entity is gone client-side).
     */
    public int removalSample(int id, double playerX, double playerZ) {
        Entry entry = book.remove(id);
        if (entry == null || !entry.hasPosition || entry.moved || entry.ridden || suppressed()) {
            return NO_SAMPLE;
        }
        int distanceBlocks = distance(playerX, playerZ, entry.x, entry.z) - HAIRCUT_BLOCKS;
        return distanceBlocks > 0 ? distanceBlocks : NO_SAMPLE;
    }

    /** Main side, feed 2 and the sweep: same gate as removals but the entry is kept. */
    public int seedSample(int id, double playerX, double playerZ) {
        Entry entry = book.get(id);
        if (entry == null || !entry.hasPosition || entry.moved || entry.ridden || suppressed()) {
            return NO_SAMPLE;
        }
        int distanceBlocks = distance(playerX, playerZ, entry.x, entry.z) - HAIRCUT_BLOCKS;
        return distanceBlocks > 0 ? distanceBlocks : NO_SAMPLE;
    }

    private void armWindow() {
        windowDeadline = nanos.getAsLong() + WINDOW_NANOS;
        sweepArmGeneration.incrementAndGet(); // every arming re-arms the pending sweep, uniformly
    }

    /** Netty side: a teed PlayerPosition or Respawn packet. */
    public void onAnomalyPacket() {
        armWindow();
    }

    /** Netty side: a teed Respawn additionally invalidates every held id. */
    public void onRespawn() {
        book.clear();
        armWindow();
    }

    /** Netty side: any SetCamera latches flag A; the id is deliberately not read. */
    public void onSetCamera() {
        cameraLatchGeneration.incrementAndGet();
    }

    /**
     * Main side, the gate-arm step at the top of every capture tick, before the sweep and the prime loop. Arms the
     * window on a fast tick, mirrors the applied camera state into flag B both directions, and consumes flag A only
     * after the applied state has been attached across a full tick boundary (the generation observed before the
     * boundary is cleared, so a detach queued mid-consume is never eaten).
     */
    public void gateArmTick(double displacementBlocks, boolean detachedApplied) {
        if (displacementBlocks > FAST_TICK_BLOCKS) {
            armWindow();
        }
        if (!detachedApplied && !prevDetached) {
            cameraConsumedGeneration = previousLatchGeneration;
        }
        prevDetached = detachedApplied;
        previousLatchGeneration = cameraLatchGeneration.get();
        cameraDetachedApplied = detachedApplied;
    }

    /** Whether the anomaly window or the camera hold is armed; while true no feed commits a sample. */
    public boolean suppressed() {
        if (nanos.getAsLong() < windowDeadline) {
            return true;
        }
        return cameraDetachedApplied || cameraLatchGeneration.get() != cameraConsumedGeneration;
    }

    /** Main side: the arm generation the due sweep would consume, or 0 when none is due or it is not quiet. */
    public int sweepBeginGeneration() {
        int generation = sweepArmGeneration.get();
        if (generation == sweptGeneration || suppressed()) {
            return 0;
        }
        return generation;
    }

    /** Main side: bits-clear anchored entries, the sweep's candidate set. */
    public int[] sweepIds() {
        return book.entrySet().stream()
                .filter(entry -> entry.getValue().hasPosition && !entry.getValue().moved && !entry.getValue().ridden)
                .mapToInt(Map.Entry::getKey)
                .toArray();
    }

    /** Main side: mark the arming consumed; a mid-sweep re-arm leaves the generations unequal. */
    public void sweepComplete(int generation) {
        sweptGeneration = generation;
    }

    private static int distance(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return (int) Math.floor(Math.sqrt(dx * dx + dz * dz));
    }
}
