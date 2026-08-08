// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * The automated guard for the mob persistence restoration and its {@code forceMobPersistence} extension.
 * {@code PersistenceRequired} is server-authoritative and arrives {@code false} on the client, so a name-tagged
 * pet/villager despawns after the downloaded world is opened. Capture must stamp it back, unconditionally, on every
 * captured named {@code Mob} (vanilla NameTagItem semantics); likewise on a mob whose equipment proves a loot pickup
 * (an item impossible for its natural spawn); and with {@code forceMobPersistence} on it stamps every captured
 * {@code Mob}, named or not.
 *
 * <p>This test pins the on-disk contract directly by driving {@code applyMobPersistence} with the derived flags: the
 * exact NBT key, its boolean type, and which combinations of named / loot-equipped / unnamed mob / non-mob and knob
 * state get the flag written. A real {@code Mob} is constructible headless (its constructor does not dereference a null
 * {@code Level}); the {@code RiddenMountMob} double in {@code EntitySinkRootVehicleTest} exercises the Mob-ness and
 * custom-name derivation through {@code captureRootVehicle}. The loot-equipped scan
 * ({@link NaturalEquipment#wasLootEquipped}) is not exercised headless.
 */
class NamedMobPersistenceTest {
    @Test
    void namedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, true, true, false, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired"),
                "a captured named mob must persist even with forceMobPersistence off (the unconditional fix)");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, true, true, false, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired"),
                "a captured named mob must persist with forceMobPersistence on as well");
    }

    @Test
    void lootEquippedMobGetsPersistenceRequiredTrueRegardlessOfKnob() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, true, false, true, false);
        assertTrue(knobOff.getBoolean("PersistenceRequired"),
                "a mob that picked up an impossible item must persist even with forceMobPersistence off");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, true, false, true, true);
        assertTrue(knobOn.getBoolean("PersistenceRequired"),
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
        assertTrue(knobOn.getBoolean("PersistenceRequired"),
                "an un-named mob must persist once forceMobPersistence is on");
    }

    @Test
    void nonMobIsNeverTouched() {
        CompoundTag knobOff = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOff, false, false, false, false);
        assertFalse(knobOff.contains("PersistenceRequired"),
                "a non-Mob entity never gets PersistenceRequired set");

        CompoundTag knobOn = new CompoundTag();
        EntitySinkImpl.applyMobPersistence(knobOn, false, false, false, true);
        assertFalse(knobOn.contains("PersistenceRequired"),
                "forceMobPersistence applies to mobs only; a non-Mob entity is left untouched");
    }
}
