// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * {@link #registerArrival} and {@link #registerSeed} never check {@link #suppressed}, and neither clears an entry's
 * moved or ridden bit.
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
     * The over-claim ceiling in blocks for a send-range sample.
     */
    public static int plausibleMaxBlocks(int renderDistanceChunks) {
        return Math.max(renderDistanceChunks, 2) * 16;
    }

    public SendRangeSampler(LongSupplier nanos, boolean cameraDetachedAtStart) {
        this.nanos = nanos;
        this.cameraDetachedApplied = cameraDetachedAtStart;
        this.prevDetached = cameraDetachedAtStart;
        armWindow();
    }

    /** Netty side: the id appeared anywhere in a {@code SetPassengers} packet; permanently feed-3-ineligible. */
    public void markRidden(int id) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(false, 0.0, 0.0, false, true)
                : new Entry(entry.hasPosition, entry.x, entry.z, entry.moved, true));
    }

    /** A nonzero delta marks the id moved, known or not; zero deltas are inert. */
    public void markMovedRelative(int id, boolean nonzeroDelta) {
        if (!nonzeroDelta) {
            return;
        }
        markMoved(id);
    }

    /**
     * Compares against the registered anchor; within the epsilon it is inert. An unknown id gets a bits-only moved
     * entry (otherwise a teleport seen before {@link #registerSeed} would leave a stale anchor with clear bits); a
     * known entry without a position is conservatively marked moved.
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

    public void markMovedRelativeTeleport(int id) {
        markMoved(id);
    }

    private void markMoved(int id) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(false, 0.0, 0.0, true, false)
                : new Entry(entry.hasPosition, entry.x, entry.z, true, entry.ridden));
    }

    public void registerArrival(int id, double x, double z) {
        book.compute(id, (key, entry) -> entry == null
                ? new Entry(true, x, z, false, false)
                : new Entry(true, x, z, entry.moved, entry.ridden));
    }

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

    public int arrivalSample(double playerX, double playerZ, double entityX, double entityZ) {
        if (suppressed()) {
            return NO_SAMPLE;
        }
        return distance(playerX, playerZ, entityX, entityZ);
    }

    public int removalSample(int id, double playerX, double playerZ) {
        Entry entry = book.remove(id);
        if (entry == null || !entry.hasPosition || entry.moved || entry.ridden || suppressed()) {
            return NO_SAMPLE;
        }
        int distanceBlocks = distance(playerX, playerZ, entry.x, entry.z) - HAIRCUT_BLOCKS;
        return distanceBlocks > 0 ? distanceBlocks : NO_SAMPLE;
    }

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
        sweepArmGeneration.incrementAndGet();
    }

    /** Netty side: a teed {@code PlayerPosition} or Respawn packet. */
    public void onAnomalyPacket() {
        armWindow();
    }

    /** Netty side: a teed Respawn additionally invalidates every held id. */
    public void onRespawn() {
        book.clear();
        armWindow();
    }

    /** Netty side: any {@code SetCamera} latches flag A; the id is deliberately not read. */
    public void onSetCamera() {
        cameraLatchGeneration.incrementAndGet();
    }

    /**
     * Arms the window on a fast tick, records {@code detachedApplied}, and consumes the camera latch only after the
     * camera has stayed attached across a full tick boundary (the generation observed before the boundary is cleared,
     * so a latch raised after that observation is never eaten).
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

    /** Whether feeds are blocked: the anomaly window is open, the camera is recorded detached or a latch unconsumed. */
    public boolean suppressed() {
        if (nanos.getAsLong() < windowDeadline) {
            return true;
        }
        return cameraDetachedApplied || cameraLatchGeneration.get() != cameraConsumedGeneration;
    }

    /** The arm generation a sweep would consume, or 0 when all armings are swept or {@link #suppressed} is true. */
    public int sweepBeginGeneration() {
        int generation = sweepArmGeneration.get();
        if (generation == sweptGeneration || suppressed()) {
            return 0;
        }
        return generation;
    }

    public int[] sweepIds() {
        return book.entrySet().stream()
                .filter(entry -> entry.getValue().hasPosition && !entry.getValue().moved && !entry.getValue().ridden)
                .mapToInt(Map.Entry::getKey)
                .toArray();
    }

    /** Record {@code generation} as swept; a re-arm during the sweep leaves the arm and swept generations unequal. */
    public void sweepComplete(int generation) {
        sweptGeneration = generation;
    }

    private static int distance(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return (int) Math.floor(Math.sqrt(dx * dx + dz * dz));
    }
}
