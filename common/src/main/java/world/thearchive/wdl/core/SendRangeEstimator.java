// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.HashMap;
import java.util.Map;

/**
 * The per-dimension entity send range for the two-tone overlay: one running max over every accepted sample, floored to
 * chunks and clamped to the live effective render distance at read. A sample is a horizontal block distance at which
 * the server demonstrably served a qualifying entity; the three feeds and every over-claim guard live in
 * SendRangeSampler and the adapter, this class only fuses accepted samples.
 *
 * Trade-offs, deliberate: the max never shrinks within a session (no sound client-side downward signal exists; a
 * decoration removal at close range is indistinguishable from the frame being broken). On a Spigot-family per-category
 * server a mob category configured larger than the decorations' category can over-claim by the category delta. A static
 * world generates no boundary events, so a motionless session cannot converge past its seed floor. Qualifying-free
 * terrain never calibrates and the cold-start mirror keeps over-claiming there. Chronic rubber-banding starves the
 * packet feeds once no 3 second suppression gap ever opens (the seed still floors calibration when a gap does open).
 * Dying calibrates typically, not always (a moving death arms the window). Moved-or-ridden entities never feed
 * removals, so mobile-mob terrain converges via arrivals only. Remaining over-claim tails, disclosed: a server stall
 * outliving the anomaly window (bounded by the plausibility bound), the category delta above, sub-threshold receding
 * arrivals (radial speed times RTT/2 past the edge; reaches 1-2 blocks on horseback at high RTT and can tip one chunk
 * on non-chunk-aligned ranges), and a trail center appended during view-distance propagation skew (recording a cap the
 * server was not yet honoring, one or two centers per view-distance change).
 *
 * MC-free and thread-safe: the packet thread observes, the client tick and async draw paths read.
 */
public final class SendRangeEstimator {
    private final Map<String, Integer> maxByDimension = new HashMap<>();

    /** Feed one accepted sample in blocks; keeps the per-dimension running max. */
    public synchronized void observe(String dimensionId, int distanceBlocks) {
        Integer current = maxByDimension.get(dimensionId);
        maxByDimension.put(dimensionId, current == null ? distanceBlocks : Math.max(current, distanceBlocks));
        // Math.max, not a > guard: the boundary mutant of a comparison survives Pitest's 100% gate.
    }

    /** Whether any sample has been accepted for this dimension. */
    public synchronized boolean isCalibrated(String dimensionId) {
        return maxByDimension.containsKey(dimensionId);
    }

    /**
     * The covered-disc radius in chunks: the floored running max, clamped to capChunks (the live effective render
     * distance). Clamping at read is deliberate: a sample proves the scaled range regardless of the later cap, so the
     * max legitimately resurfaces when the view distance rises again; the covered index's per-center caps keep the
     * retroactive paint honest.
     */
    public synchronized int radiusChunks(String dimensionId, int capChunks) {
        Integer max = maxByDimension.get(dimensionId);
        if (max == null) {
            return 0;
        }
        return Math.min(max >> 4, capChunks);
    }

    /** Drop every dimension's estimate. */
    public synchronized void clear() {
        maxByDimension.clear();
    }
}
