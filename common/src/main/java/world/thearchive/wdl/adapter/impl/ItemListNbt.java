// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jspecify.annotations.Nullable;

/**
 * The two {@code ItemStackHelper} members this band does not declare. {@code saveAllItems} and {@code loadAllItems}
 * arrive with the 1.11 empty-stack refactor; the class itself is here, and the two members it does declare
 * ({@code getAndSplit}, {@code getAndRemove}) take the slot array these take, an empty slot being null rather than a
 * sentinel stack.
 *
 * <p>The byte shape is the one every vanilla container writes and reads inline at this band, transcribed from
 * {@code TileEntityChest}: an empty slot contributes no entry, the slot index is a byte, {@code "Items"} is set even
 * when every slot was empty, and the read takes the slot byte unsigned and drops an out-of-range one in silence. A
 * captured container therefore loads as a vanilla-written one.
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
