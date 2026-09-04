// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jspecify.annotations.Nullable;

/**
 * The {@code saveAllItems} and {@code loadAllItems} this band's {@code ItemStackHelper} does not declare, transcribed
 * from {@code TileEntityChest}. A departure from that byte shape is a container vanilla reads back differently from the
 * one that was captured, with nothing to say so.
 */
public final class ItemListNbt {
    private ItemListNbt() {}

    /**
     * Set {@code "Items"} on {@code tag} from {@code items}, each entry a compound carrying its slot index followed by
     * the stack's own keys. Empty (null) slots contribute nothing, and the key is set even when the list came out
     * empty.
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
     * Read {@code "Items"} off {@code tag} into {@code items} at each entry's own slot index. The slot byte is read
     * unsigned, an entry naming a slot outside {@code items} is dropped without a word and a repeated slot overwrites;
     * a slot no entry names keeps whatever the caller left in it.
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
