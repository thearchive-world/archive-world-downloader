// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * With map id remapping on, the rewrite is <em>not</em> idempotent: a second pass would read an already-written archive
 * id as if it were a session id and re-resolve it, so a holder must be remapped exactly once.
 */
final class MapIdRemap {
    private static final String MAP_ID = "minecraft:map_id";

    private MapIdRemap() {}

    public static void remapFromItemList(CompoundTag holder, String listKey, IntUnaryOperator resolver) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, components -> remapMapId(components, resolver));
        }
    }

    public static void remapItem(CompoundTag item, IntUnaryOperator resolver) {
        ItemTreeWalk.walkItem(item, components -> remapMapId(components, resolver));
    }

    private static void remapMapId(CompoundTag components, IntUnaryOperator resolver) {
        Tag mapId = components.get(MAP_ID);
        if (mapId != null) {
            MapId.CODEC.parse(NbtOps.INSTANCE, mapId).result().ifPresent(id -> {
                int archiveId = resolver.applyAsInt(id.id());
                MapId.CODEC.encodeStart(NbtOps.INSTANCE, new MapId(archiveId)).result()
                        .ifPresent(encoded -> components.put(MAP_ID, encoded));
            });
        }
    }
}
