// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public interface LecternSink {
    /**
     * Serialize {@code book} and {@code page} into a holder {@code NBTTagCompound} carrying {@code "Book"} +
     * {@code "Page"} (an int). Assumes a non-empty book.
     */
    NBTTagCompound captureBook(ItemStack book, int page);

    /**
     * Set {@code "Book"} + {@code "Page"} on a copy of {@code lecternBlockEntityTag} from {@code capturedBookHolder},
     * leaving every other field intact. The input tag is not mutated.
     */
    NBTTagCompound merge(NBTTagCompound lecternBlockEntityTag, NBTTagCompound capturedBookHolder);
}
