// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.jspecify.annotations.Nullable;

public interface ContainerSink {
    /**
     * Serialize {@code items} (a container-sized array with each captured stack at its container-slot index, empty
     * slots are null) into a holder {@link NBTTagCompound} carrying the vanilla {@code "Items"} list (slot byte +
     * stack).
     */
    NBTTagCompound captureItems(@Nullable ItemStack[] items);

    /**
     * Set {@code "Items"} on a copy of {@code blockEntityTag} from {@code capturedItemsHolder}, leaving every other
     * field intact (id, x/y/z, {@code CustomName}, ...). The input tag is not mutated.
     */
    NBTTagCompound merge(NBTTagCompound blockEntityTag, NBTTagCompound capturedItemsHolder);
}
