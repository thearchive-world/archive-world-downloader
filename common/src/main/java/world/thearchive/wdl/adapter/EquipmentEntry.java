// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

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
