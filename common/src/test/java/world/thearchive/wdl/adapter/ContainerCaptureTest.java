// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

class ContainerCaptureTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void brewingStateRidesTheHolderWithVanillaTypes() {
        CompoundTag holder = new CompoundTag();
        ContainerCapture.putBrewingState(holder, 123, 7);
        assertEquals((short) 123, holder.getShortOr("BrewTime", (short) 0));
        assertEquals((byte) 7, holder.getByteOr("Fuel", (byte) 0));
        // The coercing getShortOr/getByteOr above would pass on a swapped short/byte too, so pin the on-disk
        // tag type directly: vanilla persists BrewTime as a short and Fuel as a byte, and the archive must match.
        assertInstanceOf(ShortTag.class, holder.get("BrewTime"), "BrewTime must be a short tag");
        assertInstanceOf(ByteTag.class, holder.get("Fuel"), "Fuel must be a byte tag");
    }

    @Test
    void zeroStateStillWritesBothKeys() {
        CompoundTag holder = new CompoundTag();
        ContainerCapture.putBrewingState(holder, 0, 0);
        assertEquals(2, holder.keySet().size());
    }

    @Test
    void crafterInputSelectionExcludesTheResultPreviewSlot() {
        SimpleContainer crafting = new SimpleContainer(9);
        crafting.setItem(0, new ItemStack(Items.STICK, 2));
        crafting.setItem(4, new ItemStack(Items.DIAMOND, 1));
        SimpleContainer result = new SimpleContainer(1);
        result.setItem(0, new ItemStack(Items.EMERALD, 64)); // the recipe preview, container index 0

        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add(new Slot(crafting, i, 0, 0));
        }
        slots.add(new Slot(result, 0, 0, 0)); // result preview: container index 0 over a DIFFERENT container

        NonNullList<ItemStack> inputs = ContainerCapture.selectCrafterInputs(slots, crafting);

        assertNotNull(inputs);
        assertEquals(9, inputs.size());
        assertEquals(Items.STICK, inputs.get(0).getItem(), "input slot 0 keeps its own stack, not the result preview");
        assertEquals(2, inputs.get(0).getCount());
        assertEquals(Items.DIAMOND, inputs.get(4).getItem());
        for (ItemStack stack : inputs) {
            assertNotEquals(Items.EMERALD, stack.getItem(), "the result-preview stack never enters the input grid");
        }
    }

    @Test
    void crafterInputSelectionKeepsAnEmptyButPresentGrid() {
        SimpleContainer crafting = new SimpleContainer(9);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add(new Slot(crafting, i, 0, 0));
        }
        NonNullList<ItemStack> inputs = ContainerCapture.selectCrafterInputs(slots, crafting);
        assertNotNull(inputs, "an empty but configured grid still captures, so disabled_slots and triggered ride it");
        assertEquals(9, inputs.size());
    }

    @Test
    void crafterInputSelectionReturnsNullWhenNoCraftingSlotPresent() {
        SimpleContainer crafting = new SimpleContainer(9);
        SimpleContainer other = new SimpleContainer(1);
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(other, 0, 0, 0)); // only a non-crafting slot backs the menu
        assertNull(ContainerCapture.selectCrafterInputs(slots, crafting));
    }
}
