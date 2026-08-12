// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later
package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntityMenuCapabilityTest {
    // args order: vanillaNamespace, container, customScreen, abstractVillager, villager, baby, nitwit
    @Test
    void pigIsIncapable() { // vanilla, none of the three families
        assertTrue(EntityMenuCapability.isMenuIncapable(true, false, false, false, false, false, false));
    }

    @Test
    void chestMinecartIsCapable() { // ContainerEntity
        assertFalse(EntityMenuCapability.isMenuIncapable(true, true, false, false, false, false, false));
    }

    @Test
    void horseIsCapable() { // HasCustomInventoryScreen
        assertFalse(EntityMenuCapability.isMenuIncapable(true, false, true, false, false, false, false));
    }

    @Test
    void wanderingTraderIsCapable() { // AbstractVillager, not Villager
        assertFalse(EntityMenuCapability.isMenuIncapable(true, false, false, true, false, false, false));
    }

    @Test
    void employedVillagerIsCapable() { // Villager, not baby, not nitwit
        assertFalse(EntityMenuCapability.isMenuIncapable(true, false, false, true, true, false, false));
    }

    @Test
    void nitwitVillagerIsIncapable() {
        assertTrue(EntityMenuCapability.isMenuIncapable(true, false, false, true, true, false, true));
    }

    @Test
    void babyVillagerIsIncapable() {
        assertTrue(EntityMenuCapability.isMenuIncapable(true, false, false, true, true, true, false));
    }

    @Test
    void moddedMobIsCapable() { // non-minecraft namespace, unprovable
        assertFalse(EntityMenuCapability.isMenuIncapable(false, false, false, false, false, false, false));
    }

    @Test
    void moddedBabyVillagerIsCapable() { // non-minecraft namespace Villager subclass, unprovable
        assertFalse(EntityMenuCapability.isMenuIncapable(false, false, false, true, true, true, false));
    }

    @Test
    void moddedNitwitVillagerIsCapable() { // non-minecraft namespace Villager subclass, unprovable
        assertFalse(EntityMenuCapability.isMenuIncapable(false, false, false, true, true, false, true));
    }
}
