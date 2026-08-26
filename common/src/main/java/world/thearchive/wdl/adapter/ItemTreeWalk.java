// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.Consumer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * The classic-MCP walk over a serialized item tree, recursing into the items nested inside a shulker box (the
 * {@code tag.BlockEntityTag.Items} list) and a bundle (the {@code tag.Items} list). Operates only on already-serialized
 * NBT, never a live ItemStack. Two views share the one recursion: {@link #walkList}/{@link #walkItem} hand the leaf
 * each item's {@code tag} compound (the item-borne coordinate scrub reaches its keys there), and {@link #forEachItem}
 * hands the leaf each item compound itself. At this band the filled-map id is the item-level {@code Damage}, not the
 * inner {@code tag}, so the map-id pass takes the item view; a filled map carries no {@code tag} at all and the tag
 * view would never see it. Each caller supplies its own per-item leaf action.
 */
final class ItemTreeWalk {
    private static final String TAG = "tag";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";

    private ItemTreeWalk() {}

    /**
     * Apply {@code onItem} to every item in {@code items} and its nested items. Each element is item NBT (with or
     * without a leading {@code "Slot"}); a non-compound element is skipped.
     */
    static void forEachItem(NBTTagList items, Consumer<NBTTagCompound> onItem) {
        for (NBTBase element : items) {
            if (element instanceof NBTTagCompound) {
                forEachItem((NBTTagCompound) element, onItem);
            }
        }
    }

    /**
     * Apply {@code onItem} to {@code item} and every item nested inside it (a shulker box's
     * {@code tag.BlockEntityTag.Items}, a bundle's {@code tag.Items}). The leaf sees the item compound itself, so a
     * caller keying off the item-level {@code id} and {@code Damage} reaches them here.
     */
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

    /**
     * Apply {@code onTag} to every item's {@code tag} compound in {@code items} and its nested items. Each element is
     * item NBT (with or without a leading {@code "Slot"}); an item with no {@code tag} compound contributes nothing.
     */
    static void walkList(NBTTagList items, Consumer<NBTTagCompound> onTag) {
        forEachItem(items, item -> acceptTag(item, onTag));
    }

    /**
     * Apply {@code onTag} to {@code item}'s {@code tag} compound and every nested item's {@code tag} compound. An item
     * with no {@code tag} compound contributes nothing.
     */
    static void walkItem(NBTTagCompound item, Consumer<NBTTagCompound> onTag) {
        forEachItem(item, nested -> acceptTag(nested, onTag));
    }

    private static void acceptTag(NBTTagCompound item, Consumer<NBTTagCompound> onTag) {
        if (item.getTag(TAG) instanceof NBTTagCompound) {
            onTag.accept((NBTTagCompound) item.getTag(TAG));
        }
    }
}
