// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldTypeTest {
    @Test
    void voidGeneratesNoTerrain() {
        assertFalse(WorldType.VOID.generatesTerrain());
    }

    @Test
    void defaultAndFlatGenerateTerrain() {
        assertTrue(WorldType.DEFAULT.generatesTerrain());
        assertTrue(WorldType.FLAT.generatesTerrain());
    }
}
