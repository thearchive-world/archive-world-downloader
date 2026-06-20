// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The pure capture-order decision, beside {@link FlushPolicy} and {@link RecapturePolicy}: the order the
 * render-distance square is walked when newly-loaded chunks are encoded. Version-agnostic core: imports no
 * {@code net.minecraft.*} type (CI-enforced) and stays Java-8-clean, so it is cherry-pickable across era-band branches.
 *
 * <p>Under a per-tick encode budget the new-chunk encode can spill across ticks (the burst the budget bounds: the first
 * tick alone faces the whole render-distance square, and a fast fly-over keeps it full). A corner-first row-major walk
 * would restart from the same corner each tick and leave one side of the player perpetually unfilled. So the offsets
 * are ordered nearest-to-player first (by Chebyshev ring, the square distance the capture already uses), so the visible
 * area fills first and the lag never concentrates directionally.
 */
public final class CaptureOrder {
    private CaptureOrder() {}

    /**
     * The {@code (dx, dz)} offsets of the {@code (2*radius+1)} square around the player, ordered by non-decreasing
     * Chebyshev distance (the center first, then each ring outward), as a flat array of consecutive {@code dx, dz}
     * pairs (length {@code 2*(2*radius+1)^2}). Deterministic, so a caller may cache it per radius rather than rebuild
     * it each tick.
     */
    public static int[] nearestFirstOffsets(int radius) {
        int side = 2 * radius + 1;
        int[] out = new int[2 * side * side];
        int i = 0;
        out[i++] = 0; // the center ring
        out[i++] = 0;
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) { // the top and bottom rows of this ring
                out[i++] = dx;
                out[i++] = -ring;
                out[i++] = dx;
                out[i++] = ring;
            }
            for (int dz = -(ring - 1); dz <= ring - 1; dz++) { // the left and right columns (rows excluded)
                out[i++] = -ring;
                out[i++] = dz;
                out[i++] = ring;
                out[i++] = dz;
            }
        }
        return out;
    }
}
