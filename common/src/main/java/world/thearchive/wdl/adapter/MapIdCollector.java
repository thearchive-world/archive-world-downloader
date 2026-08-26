// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Reusable, band-agnostic collection of the map ids a serialized item list references, over the shared
 * {@link ItemTreeWalk} that recurses into a shulker box (the {@code BlockEntityTag.Items} list) and a bundle (the
 * {@code Items} list). At this band a filled map's id is the item-level {@code Damage} short (the map's ItemStack
 * metadata) behind the {@code id == "minecraft:filled_map"} identity gate, not the inner {@code tag."map"} the higher
 * bands carry; the pure, headless-tested half of the filled-map enumeration.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time container / vehicle / ender
 * stashes), never on a live {@code ItemStack}. Used both at finish (the stashes) and as the enumeration's automated
 * guard.
 */
final class MapIdCollector {
    private static final String FILLED_MAP_ID = "minecraft:filled_map";

    private MapIdCollector() {}

    /**
     * Add every map id referenced by the items in {@code holder}'s {@code listKey} list (each list element is item NBT,
     * with or without a leading {@code "Slot"}) into {@code out}, recursing into nested containers and bundles. A
     * missing or non-list {@code listKey} is a no-op. {@code holder} is not mutated.
     */
    public static void collectFromItemList(NBTTagCompound holder, String listKey, Set<Integer> out) {
        if (holder.getTag(listKey) instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) holder.getTag(listKey);
            ItemTreeWalk.forEachItem(list, item -> collectMapId(item, out));
        }
    }

    /**
     * Add the filled map's id to {@code out} when {@code item} is a filled map. The id is the item-level {@code Damage}
     * short, read only behind the item identity gate: {@code Damage} is the universal item field every serialized item
     * carries, so a blanket read would enroll a non-map item's durability or type as a map id.
     */
    private static void collectMapId(NBTTagCompound item, Set<Integer> out) {
        if (FILLED_MAP_ID.equals(item.getString("id"))) {
            out.add((int) item.getShort("Damage"));
        }
    }
}
