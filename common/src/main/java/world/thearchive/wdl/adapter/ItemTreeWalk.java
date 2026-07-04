// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The band-stable walk over a serialized item tree: apply a leaf action to each item's {@code components} compound,
 * then recurse into the items nested inside a shulker box (the {@code minecraft:container} component, whose elements
 * are {@code {slot, item}}) and a bundle (the {@code minecraft:bundle_contents} component, whose elements are bare item
 * NBT). Operates only on already-serialized NBT, never a live ItemStack, and uses only the post-1.20.5 component shape
 * and band-stable get/instanceof ops, so one definition stays byte-identical across the era bands. Each caller supplies
 * its own per-item leaf action.
 */
final class ItemTreeWalk {
    private static final String COMPONENTS = "components";
    private static final String CONTAINER = "minecraft:container";
    private static final String BUNDLE_CONTENTS = "minecraft:bundle_contents";
    private static final String ITEM = "item";

    private ItemTreeWalk() {}

    /**
     * Apply {@code onComponents} to every item in {@code items} and its nested items. Each element is item NBT (with or
     * without a leading {@code "Slot"}); a non-compound element is skipped.
     */
    static void walkList(ListTag items, Consumer<CompoundTag> onComponents) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag item) {
                walkItem(item, onComponents);
            }
        }
    }

    /**
     * Apply {@code onComponents} to {@code item}'s components and every nested item's components. An item with no
     * {@code components} compound is a no-op.
     */
    static void walkItem(CompoundTag item, Consumer<CompoundTag> onComponents) {
        if (!(item.get(COMPONENTS) instanceof CompoundTag components)) {
            return;
        }
        onComponents.accept(components);
        if (components.get(CONTAINER) instanceof ListTag container) {
            for (int i = 0; i < container.size(); i++) {
                if (container.get(i) instanceof CompoundTag slot && slot.get(ITEM) instanceof CompoundTag nested) {
                    walkItem(nested, onComponents);
                }
            }
        }
        if (components.get(BUNDLE_CONTENTS) instanceof ListTag bundle) {
            walkList(bundle, onComponents);
        }
    }
}
