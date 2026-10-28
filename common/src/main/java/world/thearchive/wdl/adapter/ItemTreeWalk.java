// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Two views share the one recursion: {@link #walkList}/{@link #walkItem} hand the leaf each item's {@code tag}
 * compound, and {@link #forEachItem} hands the leaf each item compound itself. At this band the filled-map id is the
 * item-level {@code Damage}, not the inner {@code tag}, so the map-id pass takes the item view.
 */
final class ItemTreeWalk {
    private static final String TAG = "tag";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";

    private ItemTreeWalk() {}

    static void forEachItem(NBTTagList items, Consumer<NBTTagCompound> onItem) {
        for (NBTBase element : items) {
            if (element instanceof NBTTagCompound) {
                forEachItem((NBTTagCompound) element, onItem);
            }
        }
    }

    static void forEachItem(NBTTagCompound item, Consumer<NBTTagCompound> onItem) {
        onItem.accept(item);
        if (!(item.getTag(TAG) instanceof NBTTagCompound)) {
            return;
        }
        NBTTagCompound tag = (NBTTagCompound) item.getTag(TAG);
        if (tag.getTag(BLOCK_ENTITY_TAG) instanceof NBTTagCompound) {
            NBTTagCompound blockEntityTag = (NBTTagCompound) tag.getTag(BLOCK_ENTITY_TAG);
            if (blockEntityTag.getTag(ITEMS) instanceof NBTTagList) {
                forEachItem((NBTTagList) blockEntityTag.getTag(ITEMS), onItem);
            }
        }
        if (tag.getTag(ITEMS) instanceof NBTTagList) {
            forEachItem((NBTTagList) tag.getTag(ITEMS), onItem);
        }
    }

    static void walkList(NBTTagList items, Consumer<NBTTagCompound> onTag) {
        forEachItem(items, item -> acceptTag(item, onTag));
    }

    static void walkItem(NBTTagCompound item, Consumer<NBTTagCompound> onTag) {
        forEachItem(item, nested -> acceptTag(nested, onTag));
    }

    private static void acceptTag(NBTTagCompound item, Consumer<NBTTagCompound> onTag) {
        if (item.getTag(TAG) instanceof NBTTagCompound) {
            onTag.accept((NBTTagCompound) item.getTag(TAG));
        }
    }
}
