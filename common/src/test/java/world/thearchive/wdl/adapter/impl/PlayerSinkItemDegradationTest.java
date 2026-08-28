// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.PlayerSink;
import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the equipment loss an item component the disk codec rejects causes on the player capture. On
 * this band the save throws on such a stack rather than absorbing it, so the capture loses the whole player rather than
 * one entry. {@link PlayerSinkImpl} repairs the stack before the save, so these tests read the captured NBT back rather
 * than checking that a tag came out, and they cover the restore left behind by a failure partway through the swap
 * pass.
 */
class PlayerSinkItemDegradationTest {
    /** This band writes worn items into the Inventory list: armor at 100 plus its index, the offhand at 150. */
    private static final int OFFHAND_SLOT = 150;
    private static final int HEAD_SLOT = 103;

    private final PlayerSink sink = new PlayerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static ItemStack badBow(RegistryAccess registries) {
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.DAMAGE, -1); // a damage below zero is rejected on save
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), bow).error().isPresent(),
                "precondition: the crafted bow is genuinely unsavable");
        return bow;
    }

    /** The vanilla writer stores the slot as a byte, so an armor or offhand index above 127 reads back negative. */
    private static ItemStack capturedInventorySlot(RegistryAccess registries, CompoundTag captured, int slot) {
        for (Tag element : captured.getList("Inventory", 10)) {
            if (element instanceof CompoundTag entry && entry.getByte("Slot") == (byte) slot) {
                return ItemStack.parse(registries, entry).orElse(ItemStack.EMPTY);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void assertSavable(RegistryAccess registries, ItemStack stack, String message) {
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), stack).error().isEmpty(), message);
    }

    @Test
    void anUnsavableWornStackCostsNeitherItselfNorTheRestOfTheEquipment() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack bow = badBow(registries);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        HeadlessPlayer player = new HeadlessPlayer();
        player.setItemSlot(EquipmentSlot.OFFHAND, bow);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);

        CompoundTag captured = sink.capturePlayer(player, registries);

        ItemStack savedBow = capturedInventorySlot(registries, captured, OFFHAND_SLOT);
        assertFalse(savedBow.isEmpty(),
                "the repaired bow reached the captured NBT, not silently dropped");
        assertSavable(registries, savedBow, "the captured bow is savable (repaired)");
        assertFalse(capturedInventorySlot(registries, captured, HEAD_SLOT).isEmpty(),
                "the worn helmet lands too, so the rejected stack costs only its own entry");
        assertSame(bow, player.getItemBySlot(EquipmentSlot.OFFHAND),
                "the live equipment is restored to the original instance");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries),
                player.getItemBySlot(EquipmentSlot.OFFHAND)).error().isPresent(),
                "and still carries the rejected damage, so the repair never landed on the live stack");
    }

    @Test
    void aFailurePartwayThroughTheSwapPassStillRestoresTheLiveStacks() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack bow = badBow(registries);
        HeadlessPlayer player = new HeadlessPlayer();
        player.setItemSlot(EquipmentSlot.OFFHAND, bow);
        player.failReadingSaddle = true;

        assertThrows(IllegalStateException.class, () -> sink.capturePlayer(player, registries));

        assertSame(bow, player.getItemBySlot(EquipmentSlot.OFFHAND),
                "a throw after the first swap still restores it, so the live player never keeps a repaired copy");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries),
                player.getItemBySlot(EquipmentSlot.OFFHAND)).error().isPresent(),
                "and the restored stack is the unrepaired original");
    }

    /** A headless Player double carrying caller-set equipment, able to fail one slot read on demand. */
    private static final class HeadlessPlayer extends Player {
        private boolean failReadingSaddle;

        private HeadlessPlayer() {
            super(HeadlessLevel.get(), BlockPos.ZERO, 0.0F, new GameProfile(UUID.randomUUID(), "wdl-test"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            if (this.failReadingSaddle && slot == EquipmentSlot.BODY) {
                throw new IllegalStateException("injected failure partway through the equipment pass");
            }
            return super.getItemBySlot(slot);
        }

        @Override
        public void onEquipItem(EquipmentSlot slot, ItemStack oldItem, ItemStack newItem) {}
    }
}
