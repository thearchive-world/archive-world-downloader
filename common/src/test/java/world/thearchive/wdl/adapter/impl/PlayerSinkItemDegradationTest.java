// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.PlayerSink;
import world.thearchive.wdl.testsupport.BadStacks;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the item loss an item component the disk codec rejects causes on the player capture. Where
 * the save absorbs that error instead of throwing, the worn slots go as one field and are lost together while a stowed
 * slot loses only its own entry, and either way the capture returns a tag that reports nothing. {@link PlayerSinkImpl}
 * repairs the stack before the save, so these tests read the captured NBT back rather than checking that a tag came
 * out, and they cover the held slot both passes reach and the restore left behind by a failure partway through the swap
 * pass.
 */
class PlayerSinkItemDegradationTest {
    private static final int STOWED_SLOT = 5;

    private final PlayerSink sink = new PlayerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    private static ItemStack badBow(RegistryAccess registries) {
        Holder<Enchantment> power = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.ENCHANTMENTS, BadStacks.enchantments(registries, Map.of(power, 0)));
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), bow).error().isPresent(),
                "precondition: the crafted bow is genuinely unsavable");
        return bow;
    }

    private static ItemStack capturedInventorySlot(RegistryAccess registries, CompoundTag captured, int slot) {
        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, registries, captured);
        for (ItemStackWithSlot entry : in.listOrEmpty("Inventory", ItemStackWithSlot.CODEC)) {
            if (entry.slot() == slot) {
                return entry.stack();
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

        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, registries, captured);
        EntityEquipment saved = in.read("equipment", EntityEquipment.CODEC).orElseGet(EntityEquipment::new);
        assertFalse(saved.get(EquipmentSlot.OFFHAND).isEmpty(),
                "the repaired bow reached the captured NBT, not silently dropped");
        assertSavable(registries, saved.get(EquipmentSlot.OFFHAND), "the captured bow is savable (repaired)");
        assertFalse(saved.get(EquipmentSlot.HEAD).isEmpty(),
                "the worn helmet lands too, so one rejected stack does not cost the whole equipment field");
        assertSame(bow, player.getItemBySlot(EquipmentSlot.OFFHAND),
                "the live equipment is restored to the original instance");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries),
                player.getItemBySlot(EquipmentSlot.OFFHAND)).error().isPresent(),
                "and still carries the level-0 enchant, so the repair never landed on the live stack");
    }

    @Test
    void anUnsavableStowedStackIsCapturedRepaired() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, -1); // a damage below zero is rejected on save
        HeadlessPlayer player = new HeadlessPlayer();
        player.getInventory().setItem(STOWED_SLOT, sword);
        assertNotEquals(STOWED_SLOT, player.getInventory().getSelectedSlot(),
                "precondition: the stowed slot is not the held one, so only the Inventory pass reaches it");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries), sword).error().isPresent(),
                "precondition: the stowed sword is genuinely unsavable");

        CompoundTag captured = sink.capturePlayer(player, registries);

        ItemStack savedSword = capturedInventorySlot(registries, captured, STOWED_SLOT);
        assertFalse(savedSword.isEmpty(), "the repaired sword reached the captured Inventory, not silently dropped");
        assertSavable(registries, savedSword, "the captured sword is savable (repaired)");
        assertSame(sword, player.getInventory().getItem(STOWED_SLOT),
                "the live slot is restored to the original instance");
        assertTrue(ItemStack.CODEC.encodeStart(BadStacks.ops(registries),
                player.getInventory().getItem(STOWED_SLOT)).error().isPresent(),
                "and still carries the rejected damage, so the repair never landed on the live stack");
    }

    @Test
    void anUnsavableHeldStackIsCapturedRepaired() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        ItemStack bow = badBow(registries);
        HeadlessPlayer player = new HeadlessPlayer();
        int held = player.getInventory().getSelectedSlot();
        player.setItemSlot(EquipmentSlot.MAINHAND, bow);
        assertSame(bow, player.getInventory().getItem(held),
                "precondition: the held slot is reached by both the equipment pass and the Inventory pass");

        CompoundTag captured = sink.capturePlayer(player, registries);

        ItemStack savedBow = capturedInventorySlot(registries, captured, held);
        assertFalse(savedBow.isEmpty(), "the repaired bow reached the captured Inventory, not silently dropped");
        assertSavable(registries, savedBow, "the captured bow is savable (repaired)");
        assertSame(bow, player.getInventory().getItem(held), "the live held slot is restored to the original");
        assertSame(bow, player.getItemBySlot(EquipmentSlot.MAINHAND), "and reads back as the original either way");
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
            super(HeadlessLevel.get(), new GameProfile(UUID.randomUUID(), "wdl-test"));
        }

        @Override
        public GameType gameMode() {
            return GameType.SURVIVAL;
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            if (this.failReadingSaddle && slot == EquipmentSlot.SADDLE) {
                throw new IllegalStateException("injected failure partway through the equipment pass");
            }
            return super.getItemBySlot(slot);
        }

        @Override
        public void onEquipItem(EquipmentSlot slot, ItemStack oldItem, ItemStack newItem) {}
    }
}
