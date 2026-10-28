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
            if (items.get(i) instanceof CompoundTag) {
                CompoundTag item = (CompoundTag) items.get(i);
                walkItem(item, onLeaf);
            }
        }
    }

    static void walkItem(CompoundTag item, Consumer<CompoundTag> onLeaf) {
        if (!(item.get(TAG) instanceof CompoundTag)) {
            return;
        }
        CompoundTag tag = (CompoundTag) item.get(TAG);
        onLeaf.accept(tag);
        CompoundTag blockEntityTag = tag.get(BLOCK_ENTITY_TAG) instanceof CompoundTag
                ? (CompoundTag) tag.get(BLOCK_ENTITY_TAG)
                : null;
        if (blockEntityTag != null && blockEntityTag.get(ITEMS) instanceof ListTag) {
            ListTag shulkerItems = (ListTag) blockEntityTag.get(ITEMS);
            walkList(shulkerItems, onLeaf);
        }
        if (tag.get(ITEMS) instanceof ListTag) {
            ListTag ownItems = (ListTag) tag.get(ITEMS);
            walkList(ownItems, onLeaf);
        }
    }
}
