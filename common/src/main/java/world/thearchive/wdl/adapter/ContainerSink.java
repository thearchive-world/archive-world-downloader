// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Per-band container-capture axis: serialize an open container's items into the vanilla block-entity {@code "Items"}
 * NBT and merge them into a captured block-entity tag. Container contents are world data the client receives only while
 * the player has the container open (via {@code ClientboundContainerSetContentPacket}), never in the chunk packet, so a
 * captured chunk's chest is structurally present but empty until this axis fills it in.
 *
 * <p>The live step ({@link #captureItems(NonNullList)}) serializes the items lifted from the open menu's container
 * slots. The pure step ({@link #merge(CompoundTag, CompoundTag)}) sets {@code "Items"} on a copy of an already-captured
 * block-entity tag.
 *
 * <p>Per-band from the start: vanilla serializes items via {@code ContainerHelper.saveAllItems}, whose signature
 * follows the entity seam, so each band has its own implementation.
 */
public interface ContainerSink {
    /**
     * Serialize {@code items} (a container-sized list with each captured stack at its container-slot index, empty slots
     * are {@code ItemStack.EMPTY}) into a holder {@link CompoundTag} carrying the vanilla {@code "Items"} list (slot
     * byte + stack), exactly as a block entity would save it. Server-free.
     */
    CompoundTag captureItems(NonNullList<ItemStack> items);

    /**
     * Set {@code "Items"} on a copy of {@code blockEntityTag} from {@code capturedItemsHolder}, leaving every other
     * field intact (id, x/y/z, CustomName, ...): the captured chunk's block entity gains its real contents with no
     * clobber. The input tag is not mutated.
     */
    CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder);
}
