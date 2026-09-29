// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public final class RecapturePolicy {
    private RecapturePolicy() {}

    public static boolean isInEditZone(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
        return FlushPolicy.chunkDistance(chunkX, chunkZ, centerX, centerZ) <= radius;
    }

    /**
     * Returns 0 for an empty hot set and at least 1 for a non-empty one. A {@code refreshSeconds} (or
     * {@code ticksPerSecond}) of zero or less is clamped to 1 rather than dividing by zero.
     */
    public static int floorSliceSize(int hotCount, int refreshSeconds, int ticksPerSecond) {
        if (hotCount <= 0) {
            return 0;
        }
        long period = (long) Math.max(1, refreshSeconds) * Math.max(1, ticksPerSecond);
        return (int) ((hotCount + period - 1) / period);
    }

    public static boolean shouldRecapture(boolean stillBuffered, boolean stillLoaded) {
        return stillBuffered && stillLoaded;
    }
}
