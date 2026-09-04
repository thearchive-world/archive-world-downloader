// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.nio.file.Paths;
import java.util.UUID;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.HorseType;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.AnimalChest;
import net.minecraft.inventory.ContainerHorseInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.items.CapabilityItemHandler;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;

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
    // Vanilla's whole mount inventory once a chest is on, and the two worn slots it opens with.
    private static final int MOUNT_INVENTORY_SIZE = 17;
    private static final int WORN_SLOTS = 2;

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

    @Test
    void aCapturedChestReloadsIntoTheSlotsItWasCapturedFrom() {
        EntityHorse donkey = chestedDonkey();
        AnimalChest chest = new AnimalChest("HorseChest", MOUNT_INVENTORY_SIZE);
        // One stack per chest slot, sized to its own absolute index, so a shift or a drop is identifiable.
        for (int i = WORN_SLOTS; i < MOUNT_INVENTORY_SIZE; i++) {
            chest.setInventorySlotContents(i, new ItemStack(Items.APPLE, i));
        }
        EntityPlayerSP player = headlessPlayer();
        ContainerHorseInventory menu = new ContainerHorseInventory(player.inventory, chest, donkey, player);

        NBTTagCompound holder = new ContainerCapture(new VersionAdapterImpl(),
                new HeadlessPlatformBridge(Paths.get(".")), null).captureChestSlots(menu, player);
        assertTrue(holder != null, "fixture: a chested mount menu must yield a holder");

        // What the flush does: the mount's own saved tag, with only "Items" replaced by the captured holder.
        NBTTagCompound saved = donkey.writeToNBT(new NBTTagCompound());
        saved.setTag("Items", holder.getTagList("Items", 10));
        EntityHorse reloaded = new EntityHorse(HeadlessLevel.get());
        reloaded.readEntityFromNBT(saved);
        assertTrue(reloaded.isChested(), "fixture: the reloaded mount must read as chested, or nothing is read");

        NBTTagList reread = reloaded.writeToNBT(new NBTTagCompound()).getTagList("Items", 10);
        assertEquals(MOUNT_INVENTORY_SIZE - WORN_SLOTS, reread.tagCount(),
                "every captured chest stack must survive the reload, none dropped by vanilla's slot gate");
        for (int i = 0; i < reread.tagCount(); i++) {
            NBTTagCompound entry = reread.getCompoundTagAt(i);
            int slot = entry.getByte("Slot") & 255;
            assertEquals(slot, entry.getByte("Count") & 255,
                    "the stack reloaded at slot " + slot + " must be the one captured from that slot");
        }
    }

    private static EntityPlayerSP headlessPlayer() {
        NetHandlerPlayClient connection = new NetHandlerPlayClient(null, null, null,
                new GameProfile(UUID.randomUUID(), "wdl-test"));
        WorldClient level = new WorldClient(connection,
                new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                0, EnumDifficulty.NORMAL, new Profiler());
        // Only two abstract members reach the client singleton, and captureChestSlots touches neither.
        return new EntityPlayerSP(null, level, connection, new StatisticsManager()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
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
