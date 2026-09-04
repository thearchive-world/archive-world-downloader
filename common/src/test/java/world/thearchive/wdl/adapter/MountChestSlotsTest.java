// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.HorseType;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.CapabilityItemHandler;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.HeadlessLevel;

/**
 * The gate on the chest size a mount menu binds by. The bind matches that size against the open menu's chest-slot count
 * by exact equality and, on a mismatch, drops the open with no log line and no loss-report row, so a size wrong at all
 * discards every donkey and mule chest without a trace.
 */
class MountChestSlotsTest {
    // Vanilla's replaceItemInInventory addressing for a mount: 499 puts the chest on, 400 and 401 are the worn
    // slots, and 500 onward are the chest slots.
    private static final int CHEST_ITEM_SLOT = 499;
    private static final int WORN_SLOT_BASE = 400;
    private static final int CHEST_SLOT_BASE = 500;

    @Test
    void aChestedMountReportsFifteenChestSlots() {
        assertEquals(15, ContainerCapture.mountChestSize(chestedDonkey()),
                "a donkey or mule chest is fifteen slots, the count the open menu is matched against");
    }

    @Test
    void aMountThatArrivedAlreadyChestedReportsThoseFifteenSlotsToo() {
        EntityHorse donkey = new EntityHorse(HeadlessLevel.get());
        donkey.setType(HorseType.DONKEY);
        // Vanilla sizes a mount's inventory once, when it is built, and never again for a flag that lands afterward,
        // so the client holds a chested mount whose own inventory is still just the two worn slots.
        donkey.setChested(true);

        assertTrue(donkey.isChested(), "fixture: the synced flag reads chested");
        assertEquals(2, donkey.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).getSlots(),
                "fixture: the inventory behind that flag was never resized, so it is still the two worn slots");

        assertEquals(15, ContainerCapture.mountChestSize(donkey),
                "the menu offers the same fifteen chest slots either way, so the size matched against it cannot"
                        + " depend on whether the mount's own inventory was ever resized");
    }

    @Test
    void aMountCarryingNoChestReportsNothingToBindTo() {
        EntityHorse donkey = new EntityHorse(HeadlessLevel.get());
        donkey.setType(HorseType.DONKEY);
        assertFalse(donkey.isChested(), "fixture: no chest has been put on this donkey");

        assertEquals(0, ContainerCapture.mountChestSize(donkey),
                "a bind is refused on a zero, so a mount with no chest must report one and not its two worn slots");
    }

    @Test
    void theTwoSlotsBelowTheChestAreTheSaddleAndTheArmor() {
        EntityHorse horse = new EntityHorse(HeadlessLevel.get());
        assertTrue(horse.replaceItemInInventory(WORN_SLOT_BASE, new ItemStack(Items.SADDLE)),
                "fixture: the first worn slot takes a saddle");
        assertTrue(horse.replaceItemInInventory(WORN_SLOT_BASE + 1, new ItemStack(Items.IRON_HORSE_ARMOR)),
                "fixture: the second worn slot takes horse armor");

        NBTTagCompound saved = horse.writeToNBT(new NBTTagCompound());

        assertTrue(saved.hasKey("SaddleItem", 10),
                "vanilla saves the first worn slot as SaddleItem, so it is not part of the chest");
        assertTrue(saved.hasKey("ArmorItem", 10),
                "vanilla saves the second worn slot as ArmorItem, so it is not part of the chest");
    }

    @Test
    void theChestVanillaSavesBeginsPastThoseTwoSlots() {
        EntityHorse donkey = chestedDonkey();
        assertTrue(donkey.replaceItemInInventory(CHEST_SLOT_BASE, new ItemStack(Items.APPLE)),
                "fixture: the first chest slot takes a stack");

        NBTTagList items = donkey.writeToNBT(new NBTTagCompound()).getTagList("Items", 10);

        assertEquals(1, items.tagCount(), "one stack was placed, so one reaches the saved chest");
        assertEquals((byte) 2, items.getCompoundTagAt(0).getByte("Slot"),
                "vanilla's chest write starts at inventory index two, the offset the size subtracts");
    }

    @Test
    void theSizeIsEveryStackVanillaWouldSaveAsTheChest() {
        EntityHorse donkey = chestedDonkey();
        int slot = CHEST_SLOT_BASE;
        while (donkey.replaceItemInInventory(slot, new ItemStack(Items.APPLE))) {
            slot++;
        }

        NBTTagList items = donkey.writeToNBT(new NBTTagCompound()).getTagList("Items", 10);

        assertEquals(items.tagCount(), ContainerCapture.mountChestSize(donkey),
                "the bound size must be the number of stacks vanilla's own chest write can emit");
    }

    /** A donkey put through vanilla's own chest placement, which rebuilds its inventory around the chest. */
    private static EntityHorse chestedDonkey() {
        EntityHorse donkey = new EntityHorse(HeadlessLevel.get());
        donkey.setType(HorseType.DONKEY);
        assertTrue(donkey.replaceItemInInventory(CHEST_ITEM_SLOT, new ItemStack(Blocks.CHEST)),
                "fixture: the donkey must take the chest");
        assertTrue(donkey.isChested(), "fixture: the donkey now reads as chested");
        return donkey;
    }
}
