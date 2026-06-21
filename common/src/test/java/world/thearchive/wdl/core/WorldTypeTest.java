// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldTypeTest {
    @Test
    void voidNeedsNoWorldgenReconstruction() {
        assertFalse(WorldType.VOID.needsWorldgenReconstruction(),
                "the void generator uses the synced client registries, never the reconstructed worldgen");
    }

    @Test
    void defaultAndFlatNeedWorldgenReconstruction() {
        assertTrue(WorldType.DEFAULT.needsWorldgenReconstruction());
        assertTrue(WorldType.FLAT.needsWorldgenReconstruction());
    }
}
