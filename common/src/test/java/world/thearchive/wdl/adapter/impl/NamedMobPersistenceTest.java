// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class NamedMobPersistenceTest {
    @Test
    void namedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, true, true, false, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired").orElse(false),
                "a captured named mob must persist even with forceMobPersistence off");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, true, true, false, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired").orElse(false),
                "a captured named mob must persist with forceMobPersistence on as well");
    }

    @Test
    void lootEquippedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, true, false, true, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired").orElse(false),
                "a mob that picked up an impossible item must persist even with forceMobPersistence off");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, true, false, true, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired").orElse(false),
                "a loot-equipped mob persists with the force knob on too");
    }

    @Test
    void unnamedMobWithNaturalGearPersistsOnlyWhenForceKnobIsOn() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, true, false, false, false);
        assertFalse(knobOff.contains("PersistenceRequired"),
                "an un-named mob with only natural-pool gear keeps vanilla despawn behavior while the knob is off");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, true, false, false, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired").orElse(false),
                "an un-named mob must persist once forceMobPersistence is on");
    }

    @Test
    void nonMobIsNeverTouched() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, false, false, false, false);
        assertFalse(knobOff.contains("PersistenceRequired"), "a non-Mob entity never gets PersistenceRequired set");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, false, false, false, true);
        assertFalse(knobOn.contains("PersistenceRequired"),
                "forceMobPersistence applies to mobs only; a non-Mob entity is left untouched");
    }
}
