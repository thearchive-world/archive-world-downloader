// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The order the render-distance square is walked when newly-loaded chunks are captured.
 *
 * <p>Under a per-tick budget the new-chunk capture can spill across ticks. A corner-first row-major walk would restart
 * from the same corner each tick and, while new chunks keep arriving, leave one side of the square unfilled. The
 * offsets are therefore ordered nearest-to-center first, so the visible area fills first and the lag never concentrates
 * directionally.
 */
public final class CaptureOrder {
    private CaptureOrder() {}

    /**
     * The {@code (dx, dz)} offsets of the {@code (2*radius+1)} square around a center chunk, ordered by non-decreasing
     * Chebyshev distance (the center first, then each ring outward), as a flat array of consecutive {@code dx, dz}
     * pairs (length {@code 2*(2*radius+1)^2}). Deterministic, so a caller may cache it per radius rather than rebuild
     * it each tick.
     */
    public static int[] nearestFirstOffsets(int radius) {
        int side = 2 * radius + 1;
        int[] out = new int[2 * side * side];
        int i = 0;
        out[i++] = 0;
        out[i++] = 0;
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                out[i++] = dx;
                out[i++] = -ring;
                out[i++] = dx;
                out[i++] = ring;
            }
            for (int dz = -(ring - 1); dz <= ring - 1; dz++) {
                out[i++] = -ring;
                out[i++] = dz;
                out[i++] = ring;
                out[i++] = dz;
            }
        }
        return out;
    }
}
