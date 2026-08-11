// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The entity rim geometry: a chested animal's rim box is lowered from its full standing box to the chest, and a
 * tradeable villager's below its head, each growing from the feet so the footprint and ground line are untouched while
 * only the top drops.
 */
class OutlineTrackerTest {
    @Test
    void chestedRimBoxCapsTopToChestHeightKeepingFootprint() {
        AABB llamaBox = new AABB(10.0, 64.0, -5.0, 10.9, 65.87, -4.1);

        AABB rim = OutlineTracker.chestedRimBox(llamaBox);

        assertEquals(10.0, rim.minX, 1.0e-9);
        assertEquals(10.9, rim.maxX, 1.0e-9);
        assertEquals(-5.0, rim.minZ, 1.0e-9);
        assertEquals(-4.1, rim.maxZ, 1.0e-9);
        assertEquals(64.0, rim.minY, 1.0e-9);
        assertEquals(64.0 + 1.87 * 0.74, rim.maxY, 1.0e-9);
    }

    @Test
    void chestedRimBoxLowersTheTop() {
        AABB donkeyBox = new AABB(0.0, 0.0, 0.0, 1.4, 1.5, 1.4);

        AABB rim = OutlineTracker.chestedRimBox(donkeyBox);

        assertTrue(rim.maxY < donkeyBox.maxY);
        assertTrue(rim.maxY > donkeyBox.minY);
    }

    @Test
    void merchantRimBoxDropsTopBelowTheHeadKeepingFootprint() {
        AABB villagerBox = new AABB(0.0, 64.0, 0.0, 0.6, 65.95, 0.6);

        AABB rim = OutlineTracker.merchantRimBox(villagerBox);

        assertEquals(0.0, rim.minX, 1.0e-9);
        assertEquals(0.6, rim.maxX, 1.0e-9);
        assertEquals(0.0, rim.minZ, 1.0e-9);
        assertEquals(0.6, rim.maxZ, 1.0e-9);
        assertEquals(64.0, rim.minY, 1.0e-9);
        assertTrue(rim.maxY < villagerBox.maxY);
        assertTrue(rim.maxY > villagerBox.minY);
    }
}
