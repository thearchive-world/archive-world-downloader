// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import world.thearchive.wdl.adapter.LecternSink;

public final class LecternSinkImpl implements LecternSink {
    @Override
    public CompoundTag captureBook(ItemStack book, int page, RegistryAccess registries) {
        TagValueOutput out = DiscardingTagOutput.create(registries);
        out.store("Book", ItemStack.CODEC, book);
        out.putInt("Page", page);
        return out.buildResult();
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
