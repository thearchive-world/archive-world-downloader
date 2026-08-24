// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import world.thearchive.wdl.adapter.LecternSink;

/**
 * 26.1.2 lectern sink: serializes a lectern's book via vanilla's own {@code ItemStack.CODEC} on the
 * codec/{@code ValueOutput} layer (mirroring {@code LecternBlockEntity.saveAdditional}) and merges it into a captured
 * lectern block-entity tag.
 *
 * <p>Two steps (see {@link LecternSink}): {@link #captureBook} serializes the live open menu's slot-0 book and
 * {@link #merge} sets {@code "Book"}/{@code "Page"} on a copy of an already-captured lectern block-entity tag (pure, so
 * the headless round-trip guards it).
 */
public final class LecternSinkImpl implements LecternSink {
    @Override
    public CompoundTag captureBook(ItemStack book, int page, RegistryAccess registries) {
        // Lift of LecternBlockEntity.saveAdditional, server-free: a DISCARDING ProblemReporter replaces the
        // world-scoped collector (the same choice ContainerSink/EntitySink made). Assumes a non-empty book
        // (ItemStack.CODEC does not encode EMPTY); the caller guards that.
        TagValueOutput out = DiscardingTagOutput.create(registries);
        out.store("Book", ItemStack.CODEC, book);
        out.putInt("Page", page);
        return out.buildResult();
    }

    @Override
    public CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place, and
        // set only "Book"/"Page": id, x/y/z and every other field are preserved (no clobber). ItemStack.CODEC
        // serializes a stack to a compound, so the holder's "Book" is a compound.
        CompoundTag merged = lecternBlockEntityTag.copy();
        merged.put("Book", capturedBookHolder.getCompoundOrEmpty("Book"));
        merged.putInt("Page", capturedBookHolder.getIntOr("Page", 0));
        return merged;
    }
}
