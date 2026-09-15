// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.PlayerSink;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless half of the player sink: the whole-entity serialize needs a live player and stays field-tested, but the
 * inventory-only capture it falls back to reads nothing but the inventory itself, so it round-trips here. The fallback
 * exists for a finish whose whole-entity write threw, and it must be a record vanilla loads the inventory back from and
 * nothing more: a key the serialize would have written, invented here, would be a default stamped where the finish
 * could not read the truth.
 */
class PlayerSinkImplTest {
    private final PlayerSink sink = new PlayerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A player inventory with no player behind it; the serialize reads only the three stack lists. */
    private static Inventory inventory() {
        return new Inventory(null);
    }

    @Test
    void captureInventoryWritesTheThreeStackListsAtVanillaSlotsAndTheSelectedSlot() {
        Inventory live = inventory();
        live.items.set(3, new ItemStack(Items.ENDER_PEARL, 9));
        live.armor.set(1, new ItemStack(Items.IRON_CHESTPLATE));
        live.offhand.set(0, new ItemStack(Items.SHIELD));
        live.selected = 4;

        CompoundTag captured = sink.captureInventory(live);

        Inventory back = inventory();
        back.load(captured.getList("Inventory", 10));
        assertEquals(Items.ENDER_PEARL, back.items.get(3).getItem(), "the main-slot stack lands at its own slot");
        assertEquals(9, back.items.get(3).getCount());
        assertEquals(Items.IRON_CHESTPLATE, back.armor.get(1).getItem(), "armor lands at its vanilla 100-offset slot");
        assertEquals(Items.SHIELD, back.offhand.get(0).getItem(), "the offhand at its 150-offset slot");
        assertEquals(4, captured.getInt("SelectedItemSlot"), "the hotbar selection rides with the inventory");
    }

    @Test
    void captureInventoryWritesNothingButTheInventoryKeys() {
        Inventory live = inventory();
        live.items.set(0, new ItemStack(Items.ENDER_PEARL));

        CompoundTag captured = sink.captureInventory(live);

        assertEquals(new HashSet<>(Arrays.asList("Inventory", "SelectedItemSlot")), captured.getAllKeys(),
                "a salvage source carries only what it read; every other player key is the serialize's to write");
    }

    @Test
    void captureInventoryDetachesTheCapturedStacksFromTheLiveOnes() {
        Inventory live = inventory();
        ItemStack stack = new ItemStack(Items.ENDER_PEARL);
        stack.getOrCreateTag().putInt("wdl_test_marker", 1);
        live.items.set(0, stack);

        CompoundTag captured = sink.captureInventory(live);
        stack.getOrCreateTag().putInt("wdl_test_marker", 2);

        CompoundTag capturedItem = captured.getList("Inventory", 10).getCompound(0);
        assertTrue(capturedItem.contains("tag", 10), "precondition: the stack's tag was written");
        assertEquals(1, capturedItem.getCompound("tag").getInt("wdl_test_marker"),
                "a later edit of the live stack must not reach the captured record");
        assertFalse(capturedItem.getCompound("tag") == stack.getTag(),
                "the captured tag is a copy, not the live stack's own compound");
    }
}
