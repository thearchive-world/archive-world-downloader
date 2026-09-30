// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public final class VoidChunkPolicy {
    private VoidChunkPolicy() {}

    public static boolean isVoidChunk(boolean hasNonAirBlocks, boolean hasBlockEntities,
            boolean hasEntities, boolean hasContainers) {
        return !hasNonAirBlocks && !hasBlockEntities && !hasEntities && !hasContainers;
    }
}
