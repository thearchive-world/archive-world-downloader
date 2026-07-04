// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The walk over a serialized entity and its {@code "Passengers"} subtree, the tag-side analogue of
 * {@code Entity.getSelfAndPassengers} and the entity sibling of {@link ItemTreeWalk}. A ridden mount reaches disk as
 * one record holding the whole tree from its root ({@code ServerPlayer.saveParentVehicle} serializes
 * {@code player.getRootVehicle()}), and {@code ServerPlayer.loadAndSpawnParentVehicle} resolves that record per node,
 * so the record is a set of entities rather than one entity and every identity question about it is a per-node
 * question.
 *
 * <p>Identity is the node's own {@code "UUID"} decoded through {@link EntityMerge#readUuid}, never raw tag equality:
 * {@code Entity.saveWithoutId} stores one on every node it recurses into, so a node with no readable UUID is not a tag
 * this mod produced.
 */
final class EntityTreeWalk {
    private EntityTreeWalk() {}

    /**
     * Index {@code entity} and every node beneath its {@code "Passengers"} by that node's own {@code "UUID"}. The
     * values are the node compounds inside {@code entity}, so writing to one writes into the tree. A node whose UUID
     * does not decode is left out of the index, and its own passengers are still indexed.
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
