// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The pure re-capture decisions, kept beside {@link FlushPolicy}: while a chunk is still in the keep-hot buffer it can
 * be re-encoded as it changes near the player, so a placed block, a neighbor's build, or an edited sign lands in the
 * archive instead of only the chunk's first-load snapshot. This type decides the <em>which</em> and <em>how-many</em>
 * without touching MC state. Version-agnostic core: imports no {@code net.minecraft.*} type (CI-enforced) and stays
 * Java-8-clean, so it is cherry-pickable across era-band branches.
 *
 * <p>Two refresh paths feed off these decisions and run together: an always-fresh <em>edit zone</em> (the square of
 * {@link #isInEditZone} chunks around the player, re-encoded unconditionally so the player's own nearby edits,
 * including block-entity-data edits like sign text that never flip a chunk's unsaved flag, stay current), and an
 * always-on round-robin <em>floor</em> that re-encodes a {@link #floorSliceSize} slice of the hot set each tick, so the
 * whole set is refreshed within the configured period whatever its size: the refresh latency is what the period fixes,
 * while the per-tick slice scales with the hot set.
 */
public final class RecapturePolicy {
    private RecapturePolicy() {}

    /**
     * Whether a chunk is within {@code radius} (square/Chebyshev chunk distance) of the player's chunk, so it sits in
     * the always-fresh edit zone. {@code radius} 1 is the 3x3 immediate surroundings.
     */
    public static boolean isInEditZone(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
        return FlushPolicy.chunkDistance(chunkX, chunkZ, centerX, centerZ) <= radius;
    }

    /**
     * How many hot chunks the round-robin floor re-encodes per tick to cover all {@code hotCount} of them within
     * {@code refreshSeconds}: {@code ceil(hotCount / (refreshSeconds * ticksPerSecond))}. Returns 0 for an empty hot
     * set and at least 1 for a non-empty one (so the floor always makes progress). A hand-edited {@code refreshSeconds}
     * (or {@code ticksPerSecond}) of zero or less is clamped to 1 rather than dividing by zero.
     */
    public static int floorSliceSize(int hotCount, int refreshSeconds, int ticksPerSecond) {
        if (hotCount <= 0) {
            return 0;
        }
        long period = (long) Math.max(1, refreshSeconds) * Math.max(1, ticksPerSecond);
        return (int) ((hotCount + period - 1) / period); // ceil division
    }

    /**
     * Whether a re-encode candidate is still eligible: it must still be buffered (in the keep-hot set, so a flushed
     * chunk is never revived and never overwritten on disk) and its live chunk must still be loaded (the keep-hot
     * margin can outlive the loaded chunk, in which case the last buffered tag stands).
     */
    public static boolean shouldRecapture(boolean stillBuffered, boolean stillLoaded) {
        return stillBuffered && stillLoaded;
    }
}
