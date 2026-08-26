// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

/**
 * One equipment slot and the stack in it: the equipment payload {@link EntityPacketCapture} holds (merged so the latest
 * stack per slot wins) and the reconstruct applies with {@code setItemSlot}. A dedicated record rather than a
 * {@code Pair} so the accumulator's bound type stays short and the slot travels with its stack.
 */
final class EquipmentEntry {
    private final EntityEquipmentSlot slot;
    private final ItemStack stack;

    EquipmentEntry(EntityEquipmentSlot slot, ItemStack stack) {
        this.slot = slot;
        this.stack = stack;
    }

    EntityEquipmentSlot slot() {
        return slot;
    }

    ItemStack stack() {
        return stack;
    }
}
