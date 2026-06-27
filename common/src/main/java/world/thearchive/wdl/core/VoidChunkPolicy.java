// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The {@code skipVoidChunks} decision, kept MC-free so it is headless-testable independent of the encode. A captured
 * chunk is void (safe to omit) only when it carries nothing worth saving: no non-air blocks, no block-entities, no
 * captured entities, and no captured containers for its position.
 *
 * <p>The entity term is load-bearing: the {@code allCaptured} position set is the entity privacy gate, so a skipped
 * chunk's position is dropped from it. Requiring "no captured entities" before a chunk counts as void is what makes
 * that drop lossless: a boat, a name-tagged pet over a void gap, or an item frame on a one-block platform keeps an
 * otherwise-all-air chunk, and it is written normally.
 */
public final class VoidChunkPolicy {
    private VoidChunkPolicy() {}

    /** Whether the chunk carries nothing worth saving, so omitting it (and its position) loses no data. */
    public static boolean isVoidChunk(boolean hasNonAirBlocks, boolean hasBlockEntities,
            boolean hasEntities, boolean hasContainers) {
        return !hasNonAirBlocks && !hasBlockEntities && !hasEntities && !hasContainers;
    }
}
