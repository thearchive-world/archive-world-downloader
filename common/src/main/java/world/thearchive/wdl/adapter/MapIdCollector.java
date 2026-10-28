// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class MapIdCollector {
    private MapIdCollector() {}

    public static void collectFromItemList(CompoundTag holder, String listKey, Set<Integer> out) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, tag -> collectMapId(tag, out));
        }
    }

    private static void collectMapId(CompoundTag tag, Set<Integer> out) {
        if (tag.contains("map", 99)) {
            out.add(tag.getInt("map"));
        }
    }
}
