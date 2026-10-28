// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public interface LecternSink {
    /**
     * Serialize {@code book} and {@code page} into a holder {@link CompoundTag} carrying {@code "Book"} +
     * {@code "Page"} (an int). Assumes a non-empty book.
     */
    CompoundTag captureBook(ItemStack book, int page, RegistryAccess registries);

    /**
     * Set {@code "Book"} + {@code "Page"} on a copy of {@code lecternBlockEntityTag} from {@code capturedBookHolder},
     * leaving every other field intact. The input tag is not mutated.
     */
    CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder);
}
