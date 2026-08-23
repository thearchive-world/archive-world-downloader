// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Pins the natural-spawn equipment table: an item outside a type's pool in a pickup-fillable slot is a proven loot
 * pickup (persistence restored), an item inside the pool is ambiguous (left alone), the offhand is never inferred, and
 * unknown types are never inferred. Verified against decompiled 26.2.
 */
class NaturalEquipmentTest {
    @BeforeAll
    static void bootstrap() {
        // Populates BuiltInRegistries so Items.* / EntityType.* resolve and ItemStack.is works headless.
        TestRegistries.frozen();
    }

    private static ItemStack of(Item item) {
        return new ItemStack(item);
    }

    @Test
    void zombieMainhandIronToolsAreNaturalOtherWeaponsAreNot() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.IRON_SHOVEL)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.GRASS_BLOCK)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.WOODEN_SWORD)));
    }

    @Test
    void zombieArmorSixMaterialsNaturalExoticArmorNot() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.HEAD, of(Items.IRON_HELMET)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.HEAD, of(Items.COPPER_HELMET)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.FEET, of(Items.DIAMOND_BOOTS)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.HEAD, of(Items.TURTLE_HELMET)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.HEAD, of(Items.NETHERITE_HELMET)));
    }

    @Test
    void naturalHeadItemsAreCarvedOutGlobally() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.ZOMBIE, EquipmentSlot.HEAD, of(Items.CARVED_PUMPKIN)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.SKELETON, EquipmentSlot.HEAD, of(Items.JACK_O_LANTERN)));
        // Raid/patrol captain ominous banner; Paper strips the pattern to a bare white banner, match the item.
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.PILLAGER, EquipmentSlot.HEAD, of(Items.BANNER.white())));
    }

    @Test
    void perTypeMainhandPools() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.SKELETON, EquipmentSlot.MAINHAND, of(Items.BOW)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.SKELETON, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.DROWNED, EquipmentSlot.MAINHAND, of(Items.TRIDENT)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.DROWNED, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.WITHER_SKELETON, EquipmentSlot.MAINHAND,
                of(Items.STONE_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.PIGLIN, EquipmentSlot.MAINHAND, of(Items.GOLDEN_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.PIGLIN, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.PARCHED, EquipmentSlot.MAINHAND, of(Items.BOW)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.PARCHED, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
    }

    @Test
    void typeSpecificArmorPools() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.PIGLIN, EquipmentSlot.CHEST, of(Items.GOLDEN_CHESTPLATE)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityTypes.PIGLIN, EquipmentSlot.CHEST, of(Items.IRON_CHESTPLATE)));
        assertFalse(
                NaturalEquipment.isNaturalFor(EntityTypes.DROWNED, EquipmentSlot.CHEST, of(Items.LEATHER_CHESTPLATE)));
    }

    @Test
    void offhandIsNeverInferred() {
        // A piglin holds a bartering gold ingot in the offhand with no persistence (Piglin.holdInOffHand).
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.PIGLIN, EquipmentSlot.OFFHAND, of(Items.GOLD_INGOT)));
        assertFalse(NaturalEquipment.PICKUP_SLOTS.contains(EquipmentSlot.OFFHAND));
    }

    @Test
    void gearedTypeMembership() {
        assertTrue(NaturalEquipment.isGearedType(EntityTypes.ZOMBIE));
        assertTrue(NaturalEquipment.isGearedType(EntityTypes.PARCHED));
        assertFalse(NaturalEquipment.isGearedType(EntityTypes.CREEPER));
        assertTrue(NaturalEquipment.isNaturalFor(EntityTypes.CREEPER, EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
    }

    @Test
    void lootScanFlagsUnnaturalItemsInPickupSlots() {
        Mob zombie = new GearedMob(EntityTypes.ZOMBIE, Map.of(EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertTrue(NaturalEquipment.wasLootEquipped(zombie), "an out-of-pool mainhand proves the pickup ran");
    }

    @Test
    void lootScanAcceptsAllNaturalGear() {
        Mob zombie = new GearedMob(EntityTypes.ZOMBIE, Map.of(
                EquipmentSlot.MAINHAND, of(Items.IRON_SWORD),
                EquipmentSlot.HEAD, of(Items.IRON_HELMET)));
        assertFalse(NaturalEquipment.wasLootEquipped(zombie), "in-pool gear stays ambiguous, never inferred");
    }

    @Test
    void lootScanIgnoresEmptySlots() {
        Mob zombie = new GearedMob(EntityTypes.ZOMBIE, Map.of());
        assertFalse(NaturalEquipment.wasLootEquipped(zombie), "empty slots are no evidence of a pickup");
    }

    @Test
    void lootScanNeverInfersAgainstUngearedTypes() {
        Mob pig = new GearedMob(EntityTypes.PIG, Map.of(EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertFalse(NaturalEquipment.wasLootEquipped(pig), "a type with no spawn-gear profile is never inferred");
    }

    private static final class GearedMob extends Mob {
        private final Map<EquipmentSlot, ItemStack> gear;

        GearedMob(EntityType<? extends Mob> type, Map<EquipmentSlot, ItemStack> gear) {
            super(type, HeadlessLevel.get());
            this.gear = gear;
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            return gear.getOrDefault(slot, ItemStack.EMPTY);
        }
    }
}
