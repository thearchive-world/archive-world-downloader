// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;

import world.thearchive.wdl.adapter.ContainerSink;

/**
 * 1.12.2 container sink: serializes an open container's items via vanilla's own {@code ItemStackHelper.saveAllItems}
 * and merges them into a captured block-entity tag.
 *
 * <p>Two steps (see {@link ContainerSink}): {@link #captureItems} serializes the live menu's container slots and
 * {@link #merge} sets {@code "Items"} on a copy of an already-captured block-entity tag (pure, so the headless
 * round-trip guards it).
 */
public final class ContainerSinkImpl implements ContainerSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public NBTTagCompound captureItems(NonNullList<ItemStack> items) {
        // saveAllItems writes the non-empty stacks under "Items", each a compound carrying its slot index.
        NBTTagCompound tag = new NBTTagCompound();
        ItemStackHelper.saveAllItems(tag, items);
        return tag.copy();
    }

    @Override
    public NBTTagCompound merge(NBTTagCompound blockEntityTag, NBTTagCompound capturedItemsHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place, and
        // set only "Items": id, x/y/z, CustomName and every other field are preserved (no clobber).
        NBTTagCompound merged = blockEntityTag.copy();
        merged.setTag("Items", capturedItemsHolder.getTagList("Items", 10));
        return merged;
    }
}
