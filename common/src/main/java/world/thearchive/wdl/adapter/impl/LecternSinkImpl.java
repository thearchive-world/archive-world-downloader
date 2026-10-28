// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import world.thearchive.wdl.adapter.LecternSink;

public final class LecternSinkImpl implements LecternSink {
    @Override
    public NBTTagCompound captureBook(ItemStack book, int page) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Book", book.writeToNBT(new NBTTagCompound()));
        tag.setInteger("Page", page);
        return tag.copy();
    }

    @Override
    public NBTTagCompound merge(NBTTagCompound lecternBlockEntityTag, NBTTagCompound capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        NBTTagCompound merged = lecternBlockEntityTag.copy();
        merged.setTag("Book", capturedBookHolder.getCompoundTag("Book"));
        merged.setInteger("Page", capturedBookHolder.getInteger("Page"));
        return merged;
    }
}
