// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapId;

final class MapIdCollector {
    private static final String MAP_ID = "minecraft:map_id";

    private MapIdCollector() {}

    public static void collectFromItemList(CompoundTag holder, String listKey, Set<Integer> out) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, components -> collectMapId(components, out));
        }
    }

    private static void collectMapId(CompoundTag components, Set<Integer> out) {
        Tag mapId = components.get(MAP_ID);
        if (mapId != null) {
            MapId.CODEC.parse(NbtOps.INSTANCE, mapId).result().ifPresent(id -> out.add(id.id()));
        }
    }
}
