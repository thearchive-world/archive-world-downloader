// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class MapIdCollector {
    private static final String FILLED_MAP_ID = "minecraft:filled_map";

    private MapIdCollector() {}

    public static void collectFromItemList(NBTTagCompound holder, String listKey, Set<Integer> out) {
        if (holder.getTag(listKey) instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) holder.getTag(listKey);
            ItemTreeWalk.forEachItem(list, item -> collectMapId(item, out));
        }
    }

    /**
     * The id is the item-level {@code Damage} short, read only behind the item identity gate: {@code Damage} is the
     * universal item field every serialized item carries, so a blanket read would enroll a non-map item's durability or
     * type as a map id.
     */
    private static void collectMapId(NBTTagCompound item, Set<Integer> out) {
        if (FILLED_MAP_ID.equals(item.getString("id"))) {
            out.add((int) item.getShort("Damage"));
        }
    }
}
