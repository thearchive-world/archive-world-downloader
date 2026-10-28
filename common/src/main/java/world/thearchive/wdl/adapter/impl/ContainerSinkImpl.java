// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

import world.thearchive.wdl.adapter.ContainerSink;

public final class ContainerSinkImpl implements ContainerSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public NBTTagCompound captureItems(NonNullList<ItemStack> items) {
        NBTTagCompound tag = new NBTTagCompound();
        ItemStackHelper.saveAllItems(tag, items);
        return tag.copy();
    }

    @Override
    public NBTTagCompound merge(NBTTagCompound blockEntityTag, NBTTagCompound capturedItemsHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        NBTTagCompound merged = blockEntityTag.copy();
        merged.setTag("Items", capturedItemsHolder.getTagList("Items", 10));
        return merged;
    }
}
