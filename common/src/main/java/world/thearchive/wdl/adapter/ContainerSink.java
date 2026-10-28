// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public interface ContainerSink {
    /**
     * Serialize {@code items} into a holder {@link CompoundTag} carrying the vanilla {@code "Items"} list (slot byte +
     * stack).
     */
    CompoundTag captureItems(NonNullList<ItemStack> items);

    /**
     * Set {@code "Items"} on a copy of {@code blockEntityTag} from {@code capturedItemsHolder}, leaving every other
     * field intact (id, x/y/z, {@code CustomName}, ...). The input tag is not mutated.
     */
    CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder);
}
