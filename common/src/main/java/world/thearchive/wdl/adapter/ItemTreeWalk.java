// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class ItemTreeWalk {
    private static final String TAG = "tag";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";

    private ItemTreeWalk() {}

    static void walkList(ListTag items, Consumer<CompoundTag> onLeaf) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag item) {
                walkItem(item, onLeaf);
            }
        }
    }

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
