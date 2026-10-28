// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.ContainerSink;

public final class ContainerSinkImpl implements ContainerSink {
    @Override
    public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, items);
        return tag;
    }

    @Override
    public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        CompoundTag merged = blockEntityTag.copy();
        merged.put("Items", capturedItemsHolder.getList("Items", 10));
        return merged;
    }
}
