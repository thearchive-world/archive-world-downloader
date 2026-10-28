// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class EntityTreeWalk {
    private EntityTreeWalk() {}

    /**
     * The values are the node compounds inside {@code entity}, so writing to one writes into the tree. A node whose
     * UUID does not decode is left out of the index, and its own passengers are still indexed.
     */
    static Map<UUID, NBTTagCompound> byUuid(NBTTagCompound entity) {
        Map<UUID, NBTTagCompound> nodes = new LinkedHashMap<>();
        index(entity, nodes);
        return nodes;
    }

    private static void index(NBTTagCompound node, Map<UUID, NBTTagCompound> nodes) {
        UUID uuid = EntityMerge.readUuid(node);
        if (uuid != null) {
            nodes.put(uuid, node);
        }
        if (node.getTag("Passengers") instanceof NBTTagList) {
            NBTTagList passengers = (NBTTagList) node.getTag("Passengers");
            for (NBTBase element : passengers) {
                if (element instanceof NBTTagCompound) {
                    NBTTagCompound passenger = (NBTTagCompound) element;
                    index(passenger, nodes);
                }
            }
        }
    }
}
