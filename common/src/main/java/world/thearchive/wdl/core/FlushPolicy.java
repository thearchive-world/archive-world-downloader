// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The keep-hot flush decision: which buffered chunks are far enough from a given center chunk to stream to disk and
 * drop from memory.
 *
 * <p>Distance is the square (Chebyshev) chunk distance, matching the square render-distance region the capture walks.
 */
public final class FlushPolicy {
    private FlushPolicy() {}

    /** The square (Chebyshev) distance in chunks between two chunk positions. */
    static int chunkDistance(int aX, int aZ, int bX, int bZ) {
        return Math.max(Math.abs(aX - bX), Math.abs(aZ - bZ));
    }

    /** Whether a buffered chunk is farther than {@code keepHotRadius} from the center, so it may be flushed. */
    public static boolean shouldFlush(int chunkX, int chunkZ, int centerX, int centerZ, int keepHotRadius) {
        return chunkDistance(chunkX, chunkZ, centerX, centerZ) > keepHotRadius;
    }
}
