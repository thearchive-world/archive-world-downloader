// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.LecternSink;

public final class LecternSinkImpl implements LecternSink {
    @Override
    public CompoundTag captureBook(ItemStack book, int page, RegistryAccess registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Book", ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), book)
                .getOrThrow());
        tag.putInt("Page", page);
        return tag;
    }

    @Override
    public CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place.
        CompoundTag merged = lecternBlockEntityTag.copy();
        merged.put("Book", capturedBookHolder.getCompoundOrEmpty("Book"));
        merged.putInt("Page", capturedBookHolder.getIntOr("Page", 0));
        return merged;
    }
}
