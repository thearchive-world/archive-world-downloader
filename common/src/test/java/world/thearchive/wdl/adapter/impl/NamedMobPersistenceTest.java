// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

class NamedMobPersistenceTest {
    @Test
    void namedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        NBTTagCompound knobOff = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOff, true, true, false, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired"),
                "a captured named mob must persist even with forceMobPersistence off");

        NBTTagCompound knobOn = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOn, true, true, false, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired"),
                "a captured named mob must persist with forceMobPersistence on as well");
    }

    @Test
    void lootEquippedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        NBTTagCompound knobOff = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOff, true, false, true, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired"),
                "a mob that picked up an impossible item must persist even with forceMobPersistence off");

        NBTTagCompound knobOn = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOn, true, false, true, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired"), "a loot-equipped mob persists with the force knob on too");
    }

    @Test
    void unnamedMobWithNaturalGearPersistsOnlyWhenForceKnobIsOn() {
        NBTTagCompound knobOff = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOff, true, false, false, false);
        assertFalse(knobOff.hasKey("PersistenceRequired"),
                "an un-named mob with only natural-pool gear keeps vanilla despawn behavior while the knob is off");

        NBTTagCompound knobOn = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOn, true, false, false, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired"),
                "an un-named mob must persist once forceMobPersistence is on");
    }

    @Test
    void nonMobIsNeverTouched() {
        NBTTagCompound knobOff = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOff, false, false, false, false);
        assertFalse(knobOff.hasKey("PersistenceRequired"), "a non-mob entity never gets PersistenceRequired set");

        NBTTagCompound knobOn = new NBTTagCompound();
        EntitySinkImpl.applyMobPersistence(knobOn, false, false, false, true);
        assertFalse(knobOn.hasKey("PersistenceRequired"),
                "forceMobPersistence applies to mobs only; a non-mob entity is left untouched");
    }
}
