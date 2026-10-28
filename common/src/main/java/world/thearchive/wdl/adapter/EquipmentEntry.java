// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

record EquipmentEntry(EquipmentSlot slot, ItemStack stack) {}
