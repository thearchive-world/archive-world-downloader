// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.LecternSink;

public final class LecternSinkImpl implements LecternSink {
    @Override
    public CompoundTag captureBook(ItemStack book, int page) {
        CompoundTag tag = new CompoundTag();
        tag.put("Book", book.save(new CompoundTag()));
        tag.putInt("Page", page);
        return tag.copy();
    }

    @Override
    public CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        CompoundTag merged = lecternBlockEntityTag.copy();
        merged.put("Book", capturedBookHolder.getCompound("Book"));
        merged.putInt("Page", capturedBookHolder.getInt("Page"));
        return merged;
    }
}
