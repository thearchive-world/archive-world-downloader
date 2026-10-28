// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

final class EquipmentEntry {
    private final EquipmentSlot slot;
    private final ItemStack stack;

    EquipmentEntry(EquipmentSlot slot, ItemStack stack) {
        this.slot = slot;
        this.stack = stack;
    }

    EquipmentSlot slot() {
        return slot;
    }

    ItemStack stack() {
        return stack;
    }
}
