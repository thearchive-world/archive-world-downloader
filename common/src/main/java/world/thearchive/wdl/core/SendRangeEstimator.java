// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.HashMap;
import java.util.Map;

public final class SendRangeEstimator {
    private final Map<String, Integer> maxByDimension = new HashMap<>();

    /** Feed one accepted sample in blocks; keeps the per-dimension running max. */
    public synchronized void observe(String dimensionId, int distanceBlocks) {
        Integer current = maxByDimension.get(dimensionId);
        maxByDimension.put(dimensionId, current == null ? distanceBlocks : Math.max(current, distanceBlocks));
        // Math.max, not a > guard: the boundary mutant of a comparison survives Pitest's 100% gate.
    }

    public synchronized boolean isCalibrated(String dimensionId) {
        return maxByDimension.containsKey(dimensionId);
    }

    /**
     * The covered-disc radius in chunks: the floored running max, clamped to {@code capChunks}.
     */
    public synchronized int radiusChunks(String dimensionId, int capChunks) {
        Integer max = maxByDimension.get(dimensionId);
        if (max == null) {
            return 0;
        }
        return Math.min(max >> 4, capChunks);
    }

    public synchronized void clear() {
        maxByDimension.clear();
    }
}
