// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.SkeletonType;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Pins the 1.10.2 natural-spawn equipment table: an item outside a type's pool in a pickup-fillable slot is a proven
 * loot pickup (persistence restored), an item inside the pool is ambiguous (left alone), the offhand is never inferred,
 * and unknown types are never inferred. A mob is discriminated by its classic {@link net.minecraft.entity.EntityList}
 * registry name; the wither skeleton is not a registration here, so its key is the variant name {@code profileKey}
 * splits back out of the one {@code Skeleton} registration.
 */
class NaturalEquipmentTest {
    private static final String ZOMBIE = "Zombie";
    private static final String SKELETON = "Skeleton";
    private static final String WITHER_SKELETON = "WitherSkeleton";
    private static final String PIG_ZOMBIE = "PigZombie";
    private static final String CREEPER = "Creeper";

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
    }

    private static ItemStack of(Item item) {
        return new ItemStack(item);
    }

    @Test
    void zombieMainhandIronToolsAreNaturalOtherWeaponsAreNot() {
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.MAINHAND, of(Items.IRON_SHOVEL)));
        assertFalse(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.MAINHAND,
                of(Item.getItemFromBlock(Blocks.GRASS))));
        assertFalse(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.MAINHAND, of(Items.WOODEN_SWORD)));
    }

    @Test
    void zombieArmorFiveMaterialsNaturalExoticArmorNot() {
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.HEAD, of(Items.IRON_HELMET)));
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.FEET, of(Items.DIAMOND_BOOTS)));
        assertFalse(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.HEAD, of(Items.BANNER)));
    }

    @Test
    void naturalHeadItemsAreCarvedOutGlobally() {
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.HEAD,
                of(Item.getItemFromBlock(Blocks.PUMPKIN))));
        assertTrue(NaturalEquipment.isNaturalFor(SKELETON, EntityEquipmentSlot.HEAD,
                of(Item.getItemFromBlock(Blocks.LIT_PUMPKIN))));
    }

    @Test
    void perTypeMainhandPools() {
        assertTrue(NaturalEquipment.isNaturalFor(SKELETON, EntityEquipmentSlot.MAINHAND, of(Items.BOW)));
        assertFalse(NaturalEquipment.isNaturalFor(SKELETON, EntityEquipmentSlot.MAINHAND, of(Items.IRON_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(WITHER_SKELETON, EntityEquipmentSlot.MAINHAND,
                of(Items.STONE_SWORD)));
        assertTrue(NaturalEquipment.isNaturalFor(PIG_ZOMBIE, EntityEquipmentSlot.MAINHAND,
                of(Items.GOLDEN_SWORD)));
        assertFalse(NaturalEquipment.isNaturalFor(PIG_ZOMBIE, EntityEquipmentSlot.MAINHAND,
                of(Items.IRON_SWORD)));
    }

    @Test
    void typeSpecificArmorPools() {
        // A base-armor spawn wears any armor tier naturally; a no-armor spawn never does.
        assertTrue(NaturalEquipment.isNaturalFor(ZOMBIE, EntityEquipmentSlot.CHEST, of(Items.GOLDEN_CHESTPLATE)));
        assertFalse(NaturalEquipment.isNaturalFor(PIG_ZOMBIE, EntityEquipmentSlot.CHEST,
                of(Items.LEATHER_CHESTPLATE)));
    }

    @Test
    void offhandIsNeverInferred() {
        // The offhand is outside the inference: a mob can hold an unpersisted item there.
        assertTrue(NaturalEquipment.isNaturalFor(PIG_ZOMBIE, EntityEquipmentSlot.OFFHAND, of(Items.GOLD_INGOT)));
        assertFalse(NaturalEquipment.PICKUP_SLOTS.contains(EntityEquipmentSlot.OFFHAND));
    }

    @Test
    void gearedTypeMembership() {
        assertTrue(NaturalEquipment.isGearedType(ZOMBIE));
        assertFalse(NaturalEquipment.isGearedType(CREEPER));
        assertTrue(NaturalEquipment.isNaturalFor(CREEPER, EntityEquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD)));
    }

    @Test
    void lootScanFlagsUnnaturalItemsInPickupSlots() {
        EntityZombie zombie = zombie();
        zombie.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD));
        assertTrue(NaturalEquipment.wasLootEquipped(zombie), "an out-of-pool mainhand proves the pickup ran");
    }

    @Test
    void lootScanAcceptsAllNaturalGear() {
        EntityZombie zombie = zombie();
        zombie.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, of(Items.IRON_SWORD));
        zombie.setItemStackToSlot(EntityEquipmentSlot.HEAD, of(Items.IRON_HELMET));
        assertFalse(NaturalEquipment.wasLootEquipped(zombie), "in-pool gear stays ambiguous, never inferred");
    }

    @Test
    void lootScanIgnoresEmptySlots() {
        assertFalse(NaturalEquipment.wasLootEquipped(zombie()), "empty slots are no evidence of a pickup");
    }

    @Test
    void lootScanNeverInfersAgainstUngearedTypes() {
        EntityPig pig = new EntityPig(HeadlessLevel.get());
        pig.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, of(Items.DIAMOND_SWORD));
        assertFalse(NaturalEquipment.wasLootEquipped(pig), "a type with no spawn-gear profile is never inferred");
    }

    @Test
    void eachSkeletonVariantIsScannedAgainstItsOwnPool() {
        assertTrue(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.NORMAL, Items.DIAMOND_SWORD)),
                "a diamond sword is outside the skeleton's bow pool");
        assertFalse(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.NORMAL, Items.BOW)),
                "the bow is the skeleton's own spawn weapon");
        assertTrue(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.STRAY, Items.DIAMOND_SWORD)),
                "a diamond sword is outside the stray's bow pool");
        assertFalse(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.STRAY, Items.BOW)),
                "the stray carries the same bow the skeleton does");
        assertTrue(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.WITHER, Items.BOW)),
                "the bow is outside the wither skeleton's stone-sword pool, so the variant reaches its own profile");
        assertFalse(NaturalEquipment.wasLootEquipped(skeleton(SkeletonType.WITHER, Items.STONE_SWORD)),
                "the stone sword is the wither skeleton's own spawn weapon");
    }

    // EntityList.getEntityString (which NaturalEquipment.wasLootEquipped resolves a mob's type through) reads
    // CLASS_TO_NAME by exact runtime class, so a subclassed test double never resolves to a real name; gear is set
    // through the real EntityLivingBase.setItemStackToSlot instead of a subclass override.
    private static EntityZombie zombie() {
        return new EntityZombie(HeadlessLevel.get());
    }

    private static EntitySkeleton skeleton(SkeletonType type, Item mainhand) {
        EntitySkeleton skeleton = new EntitySkeleton(HeadlessLevel.get());
        skeleton.setSkeletonType(type);
        skeleton.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, of(mainhand));
        return skeleton;
    }
}
