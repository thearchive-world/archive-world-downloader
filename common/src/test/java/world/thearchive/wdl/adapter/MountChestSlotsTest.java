// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The reload gate on a captured mount chest. Every case sizes each stack to its own slot index, because a stack count
 * assertion passes on shifted contents, which is how this shipped past every gate the suite already had.
 */
class MountChestSlotsTest {
    // Vanilla's own slot addressing for a mount: 499 puts the chest on.
    private static final int CHEST_ITEM_SLOT = 499;
    // AbstractHorse.getSlot answers a chest index m at 498 + m, and SlotAccess.NULL past the inventory's end.
    private static final int MOUNT_SLOT_BASE = 498;
    // The chest is numbered past both worn slots whether or not this mount can fill either.
    private static final int WORN_SLOTS = 2;
    private static final int CHEST_ROWS = 3;
    // The strengths vanilla's own setter clamps a llama to.
    private static final int LOWEST_LLAMA_STRENGTH = 1;
    private static final int HIGHEST_LLAMA_STRENGTH = 5;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void aCapturedDonkeyChestReloadsIntoTheSlotsItWasCapturedFrom() {
        AbstractChestedHorse donkey = chested(new Donkey(EntityType.DONKEY, HeadlessLevel.get()));
        assertEquals(5, donkey.getInventoryColumns(),
                "fixture: a donkey's chest is five fixed columns, the fifteen slots a mule shares");

        assertTheChestReloadsWhereItWasCapturedFrom(donkey, new Donkey(EntityType.DONKEY, HeadlessLevel.get()));
    }

    @Test
    void aCapturedLlamaChestReloadsIntoTheSlotsItWasCapturedFromAtEveryStrength() {
        for (int strength = LOWEST_LLAMA_STRENGTH; strength <= HIGHEST_LLAMA_STRENGTH; strength++) {
            AbstractChestedHorse llama = chested(llamaOfStrength(strength));
            assertEquals(strength, llama.getInventoryColumns(),
                    "fixture: a llama's chest is as wide as its own strength, not a fixed count");

            assertTheChestReloadsWhereItWasCapturedFrom(llama, new Llama(EntityType.LLAMA, HeadlessLevel.get()));
        }
    }

    private static void assertTheChestReloadsWhereItWasCapturedFrom(AbstractChestedHorse mount,
            AbstractChestedHorse reloaded) {
        int inventorySize = WORN_SLOTS + CHEST_ROWS * mount.getInventoryColumns();
        // Read against vanilla's own inventory rather than a formula carried over from a sibling band.
        assertNotSame(SlotAccess.NULL, mount.getSlot(MOUNT_SLOT_BASE + inventorySize - 1),
                "fixture: the mount's own inventory must reach the last slot this fixture fills");
        assertSame(SlotAccess.NULL, mount.getSlot(MOUNT_SLOT_BASE + inventorySize),
                "fixture: and must end there, so this size is the one AbstractChestedHorse itself creates");

        SimpleContainer chest = new SimpleContainer(inventorySize);
        // One stack per chest slot, sized to its own absolute index, so a shift or a drop is identifiable per slot.
        for (int i = WORN_SLOTS; i < inventorySize; i++) {
            chest.setItem(i, new ItemStack(Items.APPLE, i));
        }
        LocalPlayer player = headlessPlayer();
        HorseInventoryMenu menu = new HorseInventoryMenu(0, player.getInventory(), chest, mount);

        CompoundTag holder = new ContainerCapture(new VersionAdapterImpl(),
                new HeadlessPlatformBridge(Paths.get(".")), TestRegistries.frozen(), null)
                        .captureChestSlots(menu, player);
        assertNotNull(holder, "fixture: a chested mount menu must yield a holder");

        // What the flush does: the mount's own saved tag, with only "Items" replaced by the captured holder.
        CompoundTag saved = mount.saveWithoutId(new CompoundTag());
        saved.put("Items", holder.getList("Items", Tag.TAG_COMPOUND));
        reloaded.readAdditionalSaveData(saved);
        assertTrue(reloaded.hasChest(), "fixture: the reloaded mount must read as chested, or nothing is read");

        int chestSlots = inventorySize - WORN_SLOTS;
        ListTag reread = reloaded.saveWithoutId(new CompoundTag()).getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < reread.size(); i++) {
            CompoundTag entry = reread.getCompound(i);
            int slot = entry.getByte("Slot") & 255;
            assertEquals(slot, entry.getByte("Count") & 255,
                    "in a " + chestSlots + "-slot chest the stack reloaded at slot " + slot
                            + " must be the one captured from that slot");
        }
        assertEquals(chestSlots, reread.size(),
                "every captured chest stack must survive the reload, none dropped by vanilla's slot gate");
    }

    /** A mount put through vanilla's own chest placement, which is what sizes its inventory around the chest. */
    private static AbstractChestedHorse chested(AbstractChestedHorse mount) {
        assertTrue(mount.getSlot(CHEST_ITEM_SLOT).set(new ItemStack(Items.CHEST)),
                "fixture: the mount must take the chest");
        assertTrue(mount.hasChest(), "fixture: the mount now reads as chested");
        return mount;
    }

    /**
     * A llama at a chosen strength. The strength has to be set before the chest goes on, because the inventory is sized
     * once, at that moment.
     */
    private static Llama llamaOfStrength(int strength) {
        Llama llama = new Llama(EntityType.LLAMA, HeadlessLevel.get());
        llama.getEntityData().set(strengthId(), strength);
        return llama;
    }

    /** The synced strength, reached by reflection because vanilla's own setter is private. */
    private static EntityDataAccessor<Integer> strengthId() {
        try {
            Field field = Llama.class.getDeclaredField("DATA_STRENGTH_ID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Integer> strength = (EntityDataAccessor<Integer>) field.get(null);
            return strength;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not reach the synced llama strength", e);
        }
    }

    /**
     * A stand-in {@link LocalPlayer}, allocated without running a constructor and given the same {@link Inventory} the
     * menu is built over. That identity is all {@link ContainerCapture#captureChestSlots} reads a player for.
     */
    private static LocalPlayer headlessPlayer() {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeType.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
            LocalPlayer player = (LocalPlayer) allocate.invoke(singleton.get(null), LocalPlayer.class);
            Field inventory = Player.class.getDeclaredField("inventory");
            inventory.setAccessible(true);
            inventory.set(player, new Inventory(player));
            return player;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a headless local player", e);
        }
    }
}
