// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * With map id remapping on, the rewrite is <em>not</em> idempotent: a second pass would read an already-written archive
 * id as if it were a session id and re-resolve it, so a holder must be remapped exactly once.
 */
final class MapIdRemap {
    private static final String FILLED_MAP_ID = "minecraft:filled_map";

    private MapIdRemap() {}

    public static void remapFromItemList(NBTTagCompound holder, String listKey, IntUnaryOperator resolver) {
        if (holder.getTag(listKey) instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) holder.getTag(listKey);
            ItemTreeWalk.forEachItem(list, item -> remapMapId(item, resolver));
        }
    }

    public static void remapItem(NBTTagCompound item, IntUnaryOperator resolver) {
        ItemTreeWalk.forEachItem(item, nested -> remapMapId(nested, resolver));
    }

    /**
     * The id is the item-level {@code Damage} short, read and written only behind the item identity gate:
     * {@code Damage} is the universal item field, so a blanket rewrite would corrupt a non-map item's durability or
     * type, and {@code Damage == 0} collides with {@code map_0}.
     */
    private static void remapMapId(NBTTagCompound item, IntUnaryOperator resolver) {
        if (!FILLED_MAP_ID.equals(item.getString("id"))) {
            return;
        }
        int old = item.getShort("Damage");
        int mapped = resolver.applyAsInt(old);
        if (mapped != old) {
            item.setShort("Damage", (short) mapped);
        }
    }
}
