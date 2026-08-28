// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ContainerSink;
import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the per-stack loss an item component the disk codec rejects causes on the container capture.
 * Where the write absorbs that error instead of throwing, the rest of the chest still lands and the archive is simply
 * missing that one item, with nothing in the capture to show it. {@link ContainerSinkImpl} repairs each stack before
 * the write, so this reads the captured {@code "Items"} list back rather than checking that a holder came out, and it
 * pins the slot each stack lands in.
 */
class ContainerSinkItemDegradationTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void anUnsavableStackIsCapturedRepairedAndItsNeighborIsUntouched() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, -1); // a damage below zero is rejected on save
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        // Non-adjacent, so a rebuild that compacted or appended would move the diamond rather than pass unnoticed.
        NonNullList<ItemStack> items = NonNullList.withSize(4, ItemStack.EMPTY);
        items.set(0, sword);
        items.set(3, diamond);
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), sword).error().isPresent(),
                "precondition: the stored sword is genuinely unsavable");

        CompoundTag holder = sink.captureItems(items, registries);

        NonNullList<ItemStack> captured = NonNullList.withSize(4, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, holder), captured);
        assertFalse(captured.get(0).isEmpty(), "the repaired sword reached the captured Items, not silently dropped");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), captured.get(0)).error().isEmpty(),
                "the captured sword is savable (repaired)");
        assertFalse(captured.get(3).isEmpty(), "the untouched neighbor still lands, at its own slot");
        assertTrue(captured.get(1).isEmpty() && captured.get(2).isEmpty(), "and the empty slots stay empty");
        assertSame(sword, items.get(0), "the live slot still holds the original instance (no lingering mutation)");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), sword).error().isPresent(),
                "the live stack still carries the rejected damage");
    }
}
