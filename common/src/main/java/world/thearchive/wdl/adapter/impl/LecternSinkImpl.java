// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import world.thearchive.wdl.adapter.LecternSink;

/**
 * 1.13.2 lectern sink: serializes a lectern's book via vanilla's own {@code ItemStack.save} (mirroring
 * {@code LecternBlockEntity.saveAdditional}) and merges it into a captured lectern block-entity tag.
 *
 * <p>Two steps (see {@link LecternSink}): {@link #captureBook} serializes the live open menu's slot-0 book and
 * {@link #merge} sets {@code "Book"}/{@code "Page"} on a copy of an already-captured lectern block-entity tag (pure, so
 * the headless round-trip guards it).
 */
public final class LecternSinkImpl implements LecternSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.save} puts the live stack's own {@code tag} compound into its output, so the
     * returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the map-id
     * remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public CompoundTag captureBook(ItemStack book, int page) {
        // Assumes a non-empty book; an empty slot 0 is dropped upstream.
        CompoundTag tag = new CompoundTag();
        tag.put("Book", book.save(new CompoundTag()));
        tag.putInt("Page", page);
        return tag.copy();
    }

    @Override
    public CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place, and
        // set only "Book"/"Page": id, x/y/z and every other field are preserved (no clobber). ItemStack.CODEC
        // serializes a stack to a compound, so the holder's "Book" is a compound.
        CompoundTag merged = lecternBlockEntityTag.copy();
        merged.put("Book", capturedBookHolder.getCompound("Book"));
        merged.putInt("Page", capturedBookHolder.getInt("Page"));
        return merged;
    }
}
