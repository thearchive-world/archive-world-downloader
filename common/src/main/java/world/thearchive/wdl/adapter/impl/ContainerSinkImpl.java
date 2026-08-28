// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import world.thearchive.wdl.adapter.ContainerSink;

/**
 * 26.1.2 container sink: serializes an open container's items via vanilla's own {@code ContainerHelper.saveAllItems}
 * (the codec/{@code ValueOutput} layer) and merges them into a captured block-entity tag.
 *
 * <p>Two steps (see {@link ContainerSink}): {@link #captureItems} serializes the live menu's container slots and
 * {@link #merge} sets {@code "Items"} on a copy of an already-captured block-entity tag (pure, so the headless
 * round-trip guards it).
 */
public final class ContainerSinkImpl implements ContainerSink {
    @Override
    public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
        // Lift of a container block entity's save path, server-free: a DISCARDING ProblemReporter replaces
        // the world-scoped collector (the same choice EntitySink made). saveAllItems writes the
        // non-empty stacks under "Items" as ItemStackWithSlot (slot index = list position) via the codec.
        TagValueOutput out = DiscardingTagOutput.create(registries);
        ContainerHelper.saveAllItems(out, sanitize(items, registries));
        return out.buildResult();
    }

    @Override
    public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
        // Copy so the captured chunk tag's block entity is replaced wholesale, never mutated in place, and
        // set only "Items": id, x/y/z, CustomName and every other field are preserved (no clobber).
        CompoundTag merged = blockEntityTag.copy();
        merged.put("Items", capturedItemsHolder.getListOrEmpty("Items"));
        return merged;
    }

    private static NonNullList<ItemStack> sanitize(NonNullList<ItemStack> items, RegistryAccess registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        NonNullList<ItemStack> clean = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            clean.set(i, ItemStackSanitizer.sanitizeForSave(items.get(i), ops));
        }
        return clean;
    }
}
