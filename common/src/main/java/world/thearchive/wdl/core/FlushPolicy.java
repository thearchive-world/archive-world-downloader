// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The keep-hot flush decision: which buffered chunks are far enough from the player to stream to disk and drop from
 * memory, so the in-memory buffer stays bounded by a hot window around the player no matter how far the capture roams.
 * Version-agnostic core: imports no {@code net.minecraft.*} type (CI-enforced) and stays Java-8-clean, so it is
 * cherry-pickable across era-band branches.
 *
 * <p>Distance is the square (Chebyshev) chunk distance, matching the square render-distance region the capture walks. A
 * chunk is kept buffered while it is within the keep-hot radius (so a container opened while the chunk is hot can still
 * be merged in before the tag leaves memory) and flushed once it is farther; the boundary is inclusive (exactly at the
 * radius is kept).
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
