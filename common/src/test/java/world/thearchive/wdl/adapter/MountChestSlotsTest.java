// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.UUID;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.passive.AbstractChestHorse;
import net.minecraft.entity.passive.EntityDonkey;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerHorseChest;
import net.minecraft.inventory.ContainerHorseInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.profiler.Profiler;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The reload gate on a captured mount chest. Every chest slot is filled with a stack sized to that slot's own absolute
 * index and read back through vanilla's own entity reader, because a stack count assertion passes on shifted contents,
 * which is how a two-slot shift shipped past every gate the suite already had.
 */
class MountChestSlotsTest {
    // Vanilla's replaceItemInInventory addressing for a mount: 499 puts the chest on.
    private static final int CHEST_ITEM_SLOT = 499;
    // The saddle slot and the armor slot lead the mount's one inventory, so the chest starts at two whether or not
    // the mount can fill either.
    private static final int WORN_SLOTS = 2;
    private static final int CHEST_ROWS = 3;
    // The strengths vanilla's own setter clamps a llama to.
    private static final int LOWEST_LLAMA_STRENGTH = 1;
    private static final int HIGHEST_LLAMA_STRENGTH = 5;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aCapturedDonkeyChestReloadsIntoTheSlotsItWasCapturedFrom() {
        AbstractChestHorse donkey = chested(new EntityDonkey(HeadlessLevel.get()));
        assertEquals(5, donkey.getInventoryColumns(),
                "fixture: a donkey's chest is five fixed columns, the fifteen slots a mule shares");

        assertTheChestReloadsWhereItWasCapturedFrom(donkey, new EntityDonkey(HeadlessLevel.get()));
    }

    @Test
    void aCapturedLlamaChestReloadsIntoTheSlotsItWasCapturedFromAtEveryStrength() {
        for (int strength = LOWEST_LLAMA_STRENGTH; strength <= HIGHEST_LLAMA_STRENGTH; strength++) {
            AbstractChestHorse llama = chested(llamaOfStrength(strength));
            assertEquals(strength, llama.getInventoryColumns(),
                    "fixture: a llama's chest is as wide as its own strength, not a fixed count");

            assertTheChestReloadsWhereItWasCapturedFrom(llama, new EntityLlama(HeadlessLevel.get()));
        }
    }

    private static void assertTheChestReloadsWhereItWasCapturedFrom(AbstractChestHorse mount,
            AbstractChestHorse reloaded) {
        int inventorySize = WORN_SLOTS + CHEST_ROWS * mount.getInventoryColumns();
        ContainerHorseChest chest = new ContainerHorseChest("HorseChest", inventorySize);
        // One stack per chest slot, sized to its own absolute index, so a shift or a drop is identifiable per slot.
        for (int i = WORN_SLOTS; i < inventorySize; i++) {
            chest.setInventorySlotContents(i, new ItemStack(Items.APPLE, i));
        }
        EntityPlayerSP player = headlessPlayer();
        ContainerHorseInventory menu = new ContainerHorseInventory(player.inventory, chest, mount, player);

        NBTTagCompound holder = new ContainerCapture(new VersionAdapterImpl(),
                new HeadlessPlatformBridge(Paths.get(".")), null).captureChestSlots(menu, player);
        assertNotNull(holder, "fixture: a chested mount menu must yield a holder");

        // What the flush does: the mount's own saved tag, with only "Items" replaced by the captured holder.
        NBTTagCompound saved = mount.writeToNBT(new NBTTagCompound());
        saved.setTag("Items", holder.getTagList("Items", 10));
        reloaded.readEntityFromNBT(saved);
        assertTrue(reloaded.hasChest(), "fixture: the reloaded mount must read as chested, or nothing is read");

        int chestSlots = inventorySize - WORN_SLOTS;
        NBTTagList reread = reloaded.writeToNBT(new NBTTagCompound()).getTagList("Items", 10);
        for (int i = 0; i < reread.tagCount(); i++) {
            NBTTagCompound entry = reread.getCompoundTagAt(i);
            int slot = entry.getByte("Slot") & 255;
            assertEquals(slot, entry.getByte("Count") & 255,
                    "in a " + chestSlots + "-slot chest the stack reloaded at slot " + slot
                            + " must be the one captured from that slot");
        }
        assertEquals(chestSlots, reread.tagCount(),
                "every captured chest stack must survive the reload, none dropped by vanilla's slot gate");
    }

    /** A mount put through vanilla's own chest placement, which is what sizes its inventory around the chest. */
    private static AbstractChestHorse chested(AbstractChestHorse mount) {
        assertTrue(mount.replaceItemInInventory(CHEST_ITEM_SLOT, new ItemStack(Blocks.CHEST)),
                "fixture: the mount must take the chest");
        assertTrue(mount.hasChest(), "fixture: the mount now reads as chested");
        return mount;
    }

    /** The strength has to be set before the chest goes on: the inventory is sized once, at that moment. */
    private static EntityLlama llamaOfStrength(int strength) {
        EntityLlama llama = new EntityLlama(HeadlessLevel.get());
        llama.getDataManager().set(strengthId(), strength);
        return llama;
    }

    /** Reached by reflection because vanilla's own strength setter is private. */
    private static DataParameter<Integer> strengthId() {
        try {
            Field field = EntityLlama.class.getDeclaredField("DATA_STRENGTH_ID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            DataParameter<Integer> strength = (DataParameter<Integer>) field.get(null);
            return strength;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the synced llama strength", e);
        }
    }

    /** A headless {@code EntityPlayerSP}, guarded against the two abstract members that reach the client singleton. */
    private static EntityPlayerSP headlessPlayer() {
        NetHandlerPlayClient connection = new NetHandlerPlayClient(null, null, null,
                new GameProfile(UUID.randomUUID(), "wdl-test"));
        WorldClient level = new WorldClient(connection,
                new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                0, EnumDifficulty.NORMAL, new Profiler());
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
}
