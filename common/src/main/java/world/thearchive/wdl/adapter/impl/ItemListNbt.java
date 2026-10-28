// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jspecify.annotations.Nullable;

public final class ItemListNbt {
    private ItemListNbt() {}

    /**
     * Set {@code "Items"} on {@code tag} from {@code items}.
     */
    public static void saveAllItems(NBTTagCompound tag, @Nullable ItemStack[] items) {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = items[i];
            if (stack != null) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setByte("Slot", (byte) i);
                stack.writeToNBT(entry);
                list.appendTag(entry);
            }
        }
        tag.setTag("Items", list);
    }

    /**
     * Read {@code "Items"} off {@code tag} into {@code items} at each entry's own slot index.
     */
    public static void loadAllItems(NBTTagCompound tag, @Nullable ItemStack[] items) {
        NBTTagList list = tag.getTagList("Items", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int slot = entry.getByte("Slot") & 255;
            if (slot >= 0 && slot < items.length) {
                items[slot] = ItemStack.loadItemStackFromNBT(entry);
            }
        }
    }
}
