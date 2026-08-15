// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The 1.20.4 walk over a serialized item tree: apply a leaf action to each item's {@code tag} compound, then recurse
 * into the items nested inside a shulker box (the {@code BlockEntityTag.Items} list) and a bundle (the {@code Items}
 * list). Operates only on already-serialized NBT, never a live ItemStack. Each caller supplies its own per-item leaf
 * action.
 */
final class ItemTreeWalk {
    private static final String TAG = "tag";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";

    private ItemTreeWalk() {}

    /**
     * Apply {@code onLeaf} to every item in {@code items} and its nested items. Each element is item NBT (with or
     * without a leading {@code "Slot"}); a non-compound element is skipped.
     */
    static void walkList(ListTag items, Consumer<CompoundTag> onLeaf) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag item) {
                walkItem(item, onLeaf);
            }
        }
    }

    /**
     * Apply {@code onLeaf} to {@code item}'s {@code tag} compound and every nested item's {@code tag} compound. An item
     * with no {@code tag} compound is a no-op.
     */
    static void walkItem(CompoundTag item, Consumer<CompoundTag> onLeaf) {
        if (!(item.get(TAG) instanceof CompoundTag tag)) {
            return;
        }
        onLeaf.accept(tag);
        if (tag.get(BLOCK_ENTITY_TAG) instanceof CompoundTag blockEntityTag
                && blockEntityTag.get(ITEMS) instanceof ListTag shulkerItems) {
            walkList(shulkerItems, onLeaf);
        }
        if (tag.get(ITEMS) instanceof ListTag bundleItems) {
            walkList(bundleItems, onLeaf);
        }
    }
}
