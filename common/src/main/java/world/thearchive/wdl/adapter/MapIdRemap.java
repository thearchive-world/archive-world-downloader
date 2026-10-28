// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * With map id remapping on, the rewrite is <em>not</em> idempotent: a second pass would read an already-written archive
 * id as if it were a session id and re-resolve it, so a holder must be remapped exactly once.
 */
final class MapIdRemap {
    private MapIdRemap() {}

    public static void remapFromItemList(CompoundTag holder, String listKey, IntUnaryOperator resolver) {
        if (holder.get(listKey) instanceof ListTag) {
            ListTag list = (ListTag) holder.get(listKey);
            ItemTreeWalk.walkList(list, tag -> remapMapId(tag, resolver));
        }
    }

    public static void remapItem(CompoundTag item, IntUnaryOperator resolver) {
        ItemTreeWalk.walkItem(item, tag -> remapMapId(tag, resolver));
    }

    private static void remapMapId(CompoundTag tag, IntUnaryOperator resolver) {
        if (tag.contains("map", 99)) {
            int old = tag.getInt("map");
            int mapped = resolver.applyAsInt(old);
            if (mapped != old) {
                tag.putInt("map", mapped);
            }
        }
    }
}
