// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import world.thearchive.wdl.adapter.LecternSink;

/**
 * 1.11.2 lectern sink: serializes a lectern's book via vanilla's own {@code ItemStack.writeToNBT} and merges it into a
 * captured lectern block-entity tag.
 *
 * <p>Documented limit: the lectern is a 1.14 block, absent at this band (it does not exist even at the 1.13.2 parent),
 * so no lectern block entity is ever captured and this sink never runs against real data. It stays wired as a compiling
 * no-op behind the band-stable SPI, the way the pre-1.14 crafter and other later features stay as unused-but-compiling
 * plugs, so the shared capture path does not fork on the band.
 *
 * <p>Two steps (see {@link LecternSink}): {@link #captureBook} serializes the live open menu's slot-0 book and
 * {@link #merge} sets {@code "Book"}/{@code "Page"} on a copy of an already-captured lectern block-entity tag.
 */
public final class LecternSinkImpl implements LecternSink {
    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so the returned tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public NBTTagCompound captureBook(ItemStack book, int page) {
        // Assumes a non-empty book; an empty slot 0 is dropped upstream.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Book", book.writeToNBT(new NBTTagCompound()));
        tag.setInteger("Page", page);
        return tag.copy();
    }

    @Override
    public NBTTagCompound merge(NBTTagCompound lecternBlockEntityTag, NBTTagCompound capturedBookHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place, and
        // set only "Book"/"Page": id, x/y/z and every other field are preserved (no clobber).
        NBTTagCompound merged = lecternBlockEntityTag.copy();
        merged.setTag("Book", capturedBookHolder.getCompoundTag("Book"));
        merged.setInteger("Page", capturedBookHolder.getInteger("Page"));
        return merged;
    }
}
