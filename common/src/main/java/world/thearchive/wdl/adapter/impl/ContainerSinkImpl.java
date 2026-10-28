// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.ContainerSink;

public final class ContainerSinkImpl implements ContainerSink {
    @Override
    public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, sanitize(items, registries), registries);
        return tag;
    }

    private static NonNullList<ItemStack> sanitize(NonNullList<ItemStack> items, RegistryAccess registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        NonNullList<ItemStack> clean = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            clean.set(i, ItemStackSanitizer.sanitizeForSave(items.get(i), ops));
        }
        return clean;
    }

    @Override
    public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        CompoundTag merged = blockEntityTag.copy();
        merged.put("Items", capturedItemsHolder.getListOrEmpty("Items"));
        return merged;
    }
}
