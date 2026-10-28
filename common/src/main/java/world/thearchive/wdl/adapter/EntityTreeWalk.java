// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

final class EntityTreeWalk {
    private EntityTreeWalk() {}

    /**
     * The values are the node compounds inside {@code entity}, so writing to one writes into the tree. A node whose
     * UUID does not decode is left out of the index, and its own passengers are still indexed.
     */
    static Map<UUID, CompoundTag> byUuid(CompoundTag entity) {
        Map<UUID, CompoundTag> nodes = new LinkedHashMap<>();
        index(entity, nodes);
        return nodes;
    }

    private static void index(CompoundTag node, Map<UUID, CompoundTag> nodes) {
        UUID uuid = EntityMerge.readUuid(node);
        if (uuid != null) {
            nodes.put(uuid, node);
        }
        if (node.get("Passengers") instanceof ListTag passengers) {
            for (int i = 0; i < passengers.size(); i++) {
                if (passengers.get(i) instanceof CompoundTag passenger) {
                    index(passenger, nodes);
                }
            }
        }
    }
}
