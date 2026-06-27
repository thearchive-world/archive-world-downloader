// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code skipVoidChunks} decision: a chunk is void (skippable) only when it carries nothing worth saving. The
 * invariant: a chunk with any captured entity, block-entity, container, or non-air block is NOT void, so dropping it
 * (and its {@code allCaptured} position) can never destroy captured data.
 */
class VoidChunkPolicyTest {
    @Test
    void anEmptyAllAirChunkIsVoid() {
        assertTrue(VoidChunkPolicy.isVoidChunk(false, false, false, false));
    }

    @Test
    void aChunkWithNonAirBlocksIsNotVoid() {
        assertFalse(VoidChunkPolicy.isVoidChunk(true, false, false, false));
    }

    @Test
    void aChunkWithBlockEntityIsNotVoid() {
        assertFalse(VoidChunkPolicy.isVoidChunk(false, true, false, false));
    }

    @Test
    void aChunkWithCapturedEntityIsNotVoid() {
        assertFalse(VoidChunkPolicy.isVoidChunk(false, false, true, false),
                "a boat or a name-tagged pet over a void gap keeps an otherwise-air chunk");
    }

    @Test
    void aChunkWithCapturedContainerIsNotVoid() {
        assertFalse(VoidChunkPolicy.isVoidChunk(false, false, false, true));
    }
}
