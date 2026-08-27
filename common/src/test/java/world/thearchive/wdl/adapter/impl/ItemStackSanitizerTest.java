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

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The unit-level guard for {@link ItemStackSanitizer} itself, exercising the repair policy directly rather than through
 * an entity or a merchant offer. This band's {@code ItemEnchantments} {@code LEVEL_CODEC} is {@code intRange(0, 255)},
 * so a level-0 enchantment encodes fine here and cannot stand in for a genuinely unsavable component the way it does on
 * later bands; {@code ItemStackSanitizer.repairEnchantments} has no fixture reachable on this band as a result.
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
    void aBowWithFailingComponentIsDroppedAndBecomesSavable() {
        // On this band ItemEnchantments.LEVEL_CODEC is intRange(0,255), so a level-0 enchant saves fine and cannot
        // stand in for a genuinely unsavable component; DAMAGE = -1 is what NON_NEGATIVE_INT actually rejects here.
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.DAMAGE, -1);
        assertFalse(savable(bow), "precondition: the crafted bow is genuinely unsavable");

        ItemStack clean = ItemStackSanitizer.sanitizeForSave(bow, ops());
        assertNotSame(bow, clean, "a repair returns a copy");
        assertTrue(savable(clean), "the sanitized bow encodes");
    }

    @Test
    void aBadNestedContainerItemIsRepairedAndSiblingsKept() {
        ItemStack badBook = new ItemStack(Items.ENCHANTED_BOOK);
        badBook.set(DataComponents.DAMAGE, -1);
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
        // An enchanted book is not damageable, so its prototype declares no DAMAGE to fall back to and the
        // failing override is removed outright.
        assertNull(back.get(0).get(DataComponents.DAMAGE), "the nested failing component is dropped");
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
