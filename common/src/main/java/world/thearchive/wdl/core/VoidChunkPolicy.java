// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The entity term is load-bearing: the {@code allCaptured} position set is the entity privacy gate, so a skipped
 * chunk's position is dropped from it. Requiring "no captured entities" before a chunk counts as void is what makes
 * that drop lossless.
 */
public final class VoidChunkPolicy {
    private VoidChunkPolicy() {}

    public static boolean isVoidChunk(boolean hasNonAirBlocks, boolean hasBlockEntities,
            boolean hasEntities, boolean hasContainers) {
        return !hasNonAirBlocks && !hasBlockEntities && !hasEntities && !hasContainers;
    }
}
