// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The unit-level guard for {@link ItemStackSanitizer} itself, exercising the repair policy directly rather than through
 * an entity or a merchant offer. A level-0 enchantment has no in-memory route, so those fixtures come from
 * {@link BadStacks#enchantments}.
 */
class ItemStackSanitizerTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static RegistryOps<Tag> ops() {
        return BadStacks.ops(TestRegistries.frozen());
    }

    private static boolean savable(ItemStack stack) {
        return ItemStack.CODEC.encodeStart(ops(), stack).error().isEmpty();
    }

    @Test
    void aSavableStackIsReturnedUnchanged() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        assertSame(sword, ItemStackSanitizer.sanitizeForSave(sword, ops()), "fast path returns the same instance");
    }

    @Test
    void aFailingComponentFallsBackToThePrototypeDefault() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, -1); // a damage below zero is rejected on save
        assertFalse(savable(sword), "precondition: the crafted stack is genuinely unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(sword, ops());

        assertNotSame(sword, clean, "a repair returns a copy");
        assertTrue(savable(clean), "the sanitized stack now encodes");
        // Absent rather than default would archive a sword that is no longer damageable at all.
        assertNotNull(clean.get(DataComponents.DAMAGE), "DAMAGE reads back present, at the prototype default");
        assertTrue(clean.isDamageableItem(), "the repaired sword is still damageable");
        ItemStack loaded = ItemStack.CODEC.parse(ops(), ItemStack.CODEC.encodeStart(ops(), clean).getOrThrow())
                .getOrThrow();
        assertTrue(loaded.isDamageableItem(), "and still damageable once written to disk and read back");
    }

    @Test
    void aFailingComponentWithNoPrototypeDefaultIsRemoved() {
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        diamond.set(DataComponents.MAX_DAMAGE, 0); // POSITIVE_INT rejects 0; a diamond has no durability
        assertFalse(savable(diamond), "precondition: the crafted stack is genuinely unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(diamond, ops());

        assertTrue(savable(clean), "the sanitized stack now encodes");
        assertNull(clean.get(DataComponents.MAX_DAMAGE), "a diamond's prototype declares no MAX_DAMAGE, so the "
                + "failing override is removed rather than set to a default that does not exist");
    }

    @Test
    void aLevelZeroEnchantIsStrippedAndValidEnchantsKept() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Holder<Enchantment> power = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER);
        Holder<Enchantment> unbreaking = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(power, 0, unbreaking, 3)));
        assertFalse(savable(bow), "precondition: level-0 enchant makes it unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(bow, ops());
        assertTrue(savable(clean), "the sanitized bow encodes");
        ItemEnchantments ench = clean.get(DataComponents.ENCHANTMENTS);
        assertEquals(0, ench.getLevel(power), "the level-0 entry is dropped");
        assertEquals(3, ench.getLevel(unbreaking), "the valid enchantment is preserved");
    }

    @Test
    void aFullyRepairedEnchantmentReadsBackPresentAndEmptyNotAbsent() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Holder<Enchantment> power = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(power, 0)));
        assertFalse(bow.isEnchantable(), "precondition: the level-0 entry occupies the slot, so the bow does not "
                + "yet read as enchantable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(bow, ops());

        assertTrue(savable(clean), "the sanitized bow encodes");
        ItemEnchantments ench = clean.get(DataComponents.ENCHANTMENTS);
        // Absent rather than present and empty would leave the bow permanently un-enchantable.
        assertNotNull(ench, "the repaired component reads back present, matching an item that was never enchanted");
        assertTrue(ench.isEmpty(), "the only entry was level 0 and is gone");
        assertTrue(clean.isEnchantable(), "a present, empty ENCHANTMENTS component makes the bow enchantable again");
    }

    @Test
    void aLevelZeroStoredEnchantWithNoPrototypeDefaultIsRemovedNotEmptied() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Holder<Enchantment> sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.STORED_ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(sharpness, 0)));
        assertFalse(savable(sword), "precondition: the level-0 stored enchant makes it unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(sword, ops());

        assertTrue(savable(clean), "the sanitized sword encodes");
        // The remove() arm the fully-repaired ENCHANTMENTS test above does not reach.
        assertNull(clean.get(DataComponents.STORED_ENCHANTMENTS), "the type has no prototype default, so it reads "
                + "back absent rather than present and empty");
    }

    @Test
    void aBadNestedContainerItemIsRepairedAndSiblingsKept() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Holder<Enchantment> mending = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
        ItemStack badBook = new ItemStack(Items.ENCHANTED_BOOK);
        badBook.set(DataComponents.STORED_ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(mending, 0)));
        NonNullList<ItemStack> inner = NonNullList.withSize(2, ItemStack.EMPTY);
        inner.set(0, badBook);
        inner.set(1, new ItemStack(Items.DIAMOND, 5));
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inner));
        assertFalse(savable(shulker), "precondition: a nested bad item makes the container unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(shulker, ops());
        assertTrue(savable(clean), "the sanitized shulker encodes");
        NonNullList<ItemStack> back = NonNullList.withSize(2, ItemStack.EMPTY);
        clean.get(DataComponents.CONTAINER).copyInto(back);
        assertEquals(Items.ENCHANTED_BOOK, back.get(0).getItem(), "the repaired nested item survives");
        assertEquals(Items.DIAMOND, back.get(1).getItem(), "the valid sibling survives");
        assertTrue(ItemStack.CODEC.encodeStart(ops(), back.get(0)).error().isEmpty(),
                "the repaired nested book is now savable");
        ItemEnchantments storedEnch = back.get(0).get(DataComponents.STORED_ENCHANTMENTS);
        assertNotNull(storedEnch, "the repaired component reads back present, matching a book with no stored enchant");
        assertTrue(storedEnch.isEmpty(), "the nested level-0 stored enchant is gone");
    }

    @Test
    void aFailingMaxStackSizeGoesAbsentBesideDamageableOverride() {
        ItemStack stone = new ItemStack(Items.STONE);
        stone.set(DataComponents.MAX_DAMAGE, 100); // a damageable override on a normally stackable item
        stone.set(DataComponents.MAX_STACK_SIZE, 0); // below the codec's floor, so the stack cannot be saved
        assertFalse(savable(stone), "precondition: the crafted stack is genuinely unsavable");
        assertFalse(stone.getOrDefault(DataComponents.MAX_STACK_SIZE, 1) > 1,
                "precondition: the crafted stack is not yet both damageable and stackable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(stone, ops());

        assertTrue(savable(clean), "the sanitized stack now encodes");
        assertTrue(clean.has(DataComponents.MAX_DAMAGE), "the valid damageable override is left alone");
        // Restoring the prototype default here would archive a stack vanilla itself refuses to build.
        assertFalse(clean.getOrDefault(DataComponents.MAX_STACK_SIZE, 1) > 1,
                "the repaired stack is not both damageable and stackable");
    }
}
