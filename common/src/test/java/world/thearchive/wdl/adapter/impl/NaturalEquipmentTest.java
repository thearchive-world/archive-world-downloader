// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Pins the natural-spawn equipment table: an item outside a type's pool in a pickup-fillable slot is a proven loot
 * pickup (persistence restored), an item inside the pool is ambiguous (left alone), the offhand is never inferred, and
 * unknown types are never inferred. Verified against decompiled 1.21.11.
 */
class NaturalEquipmentTest {
    @BeforeAll
    static void bootstrap() {
        // Populates BuiltInRegistries so Items.* / EntityType.* resolve and ItemStack.is works headless.
        TestRegistries.bootstrap();
    }

    private static ItemStack of(Item item) {
        return new ItemStack(item);
    }

    @Test
    void zombieMainhandIronToolsAreNaturalOtherWeaponsAreNot() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.IRON_SHOVEL)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.MAINHAND,
                of(Blocks.GRASS_BLOCK.asItem())));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.MAINHAND, of(Items.WOODEN_SWORD)));
    }

    @Test
    void zombieArmorSixMaterialsNaturalExoticArmorNot() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.HEAD, of(Items.IRON_HELMET)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.FEET, of(Items.DIAMOND_BOOTS)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.HEAD, of(Items.TURTLE_HELMET)));
    }

    @Test
    void naturalHeadItemsAreCarvedOutGlobally() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.HEAD,
                of(Blocks.CARVED_PUMPKIN.asItem())));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.SKELETON, EquipmentSlot.HEAD,
                of(Blocks.JACK_O_LANTERN.asItem())));
        // The white-banner head carve-out, exercised on a geared no-armor illager so the carve-out itself decides it
        // rather than an armor pool. Its raid/patrol captain ominous banner is a 1.14 feature the carve-out
        // anticipates.
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.VINDICATOR, EquipmentSlot.HEAD, of(Items.WHITE_BANNER)));
    }

    @Test
    void perTypeMainhandPools() {
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.SKELETON, EquipmentSlot.MAINHAND, of(Items.BOW)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.SKELETON, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.DROWNED, EquipmentSlot.MAINHAND, of(Items.TRIDENT)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.DROWNED, EquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.WITHER_SKELETON, EquipmentSlot.MAINHAND,
                of(Items.STONE_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE_PIGMAN, EquipmentSlot.MAINHAND,
                of(Items.GOLDEN_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE_PIGMAN, EquipmentSlot.MAINHAND,
                of(Items.IRON_SWORD)));
    }

    @Test
    void typeSpecificArmorPools() {
        // A base-armor spawn wears any armor tier naturally; a no-armor spawn never does.
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE, EquipmentSlot.CHEST, of(Items.GOLDEN_CHESTPLATE)));
        assertFalse(
                NaturalEquipment.isNaturalFor(EntityType.DROWNED, EquipmentSlot.CHEST, of(Items.LEATHER_CHESTPLATE)));
    }

    @Test
    void offhandIsNeverInferred() {
        // The offhand is outside the inference: a mob can hold an unpersisted item there.
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.ZOMBIE_PIGMAN, EquipmentSlot.OFFHAND,
                of(Items.GOLD_INGOT)));
        assertFalse(NaturalEquipment.PICKUP_SLOTS.contains(EquipmentSlot.OFFHAND));
    }

    @Test
    void gearedTypeMembership() {
        assertTrue(NaturalEquipment.isGearedType(EntityType.ZOMBIE));
        assertFalse(NaturalEquipment.isGearedType(EntityType.CREEPER));
        assertTrue(NaturalEquipment.isNaturalFor(EntityType.CREEPER, EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
    }

    @Test
    void lootScanFlagsUnnaturalItemsInPickupSlots() {
        Mob zombie = new GearedMob(EntityType.ZOMBIE, ImmutableMap.of(EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertTrue(NaturalEquipment.wasLootEquipped(zombie), "an out-of-pool mainhand proves the pickup ran");
    }

    @Test
    void lootScanAcceptsAllNaturalGear() {
        Mob zombie = new GearedMob(EntityType.ZOMBIE, ImmutableMap.of(
                EquipmentSlot.MAINHAND, of(Items.IRON_SWORD),
                EquipmentSlot.HEAD, of(Items.IRON_HELMET)));
        assertFalse(NaturalEquipment.wasLootEquipped(zombie), "in-pool gear stays ambiguous, never inferred");
    }

    @Test
    void lootScanIgnoresEmptySlots() {
        Mob zombie = new GearedMob(EntityType.ZOMBIE, ImmutableMap.of());
        assertFalse(NaturalEquipment.wasLootEquipped(zombie), "empty slots are no evidence of a pickup");
    }

    @Test
    void lootScanNeverInfersAgainstUngearedTypes() {
        Mob pig = new GearedMob(EntityType.PIG, ImmutableMap.of(EquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertFalse(NaturalEquipment.wasLootEquipped(pig), "a type with no spawn-gear profile is never inferred");
    }

    /** A Mob double whose gear is served straight from the map, avoiding equipment machinery. */
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
