// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import org.jspecify.annotations.Nullable;

final class EntityMerge {
    private EntityMerge() {}

    static int merge(NBTTagCompound onDisk, NBTTagCompound fresh) {
        if (!(fresh.getTag("Entities") instanceof NBTTagList) || !(onDisk.getTag("Entities") instanceof NBTTagList)) {
            return 0;
        }
        NBTTagList freshEntities = (NBTTagList) fresh.getTag("Entities");
        NBTTagList diskEntities = (NBTTagList) onDisk.getTag("Entities");
        Map<UUID, NBTTagCompound> diskNodes = new LinkedHashMap<>();
        for (NBTBase element : diskEntities) {
            if (element instanceof NBTTagCompound) {
                NBTTagCompound diskEntity = (NBTTagCompound) element;
                diskNodes.putAll(EntityTreeWalk.byUuid(diskEntity));
            }
        }
        Set<UUID> freshUuids = new HashSet<>();
        int merged = 0;
        for (NBTBase element : freshEntities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound freshEntity = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(freshEntity).entrySet()) {
                freshUuids.add(node.getKey());
                NBTTagCompound diskNode = diskNodes.get(node.getKey());
                if (diskNode != null) {
                    // A version that writes Xp never syncs it, so there a client entity serializes Xp at the client
                    // zero.
                    boolean carried = NbtMerge.carryList(diskNode, node.getValue(), "Items");
                    carried |= NbtMerge.carryCompound(diskNode, node.getValue(), "Offers", null);
                    carried |= NbtMerge.carryValue(diskNode, node.getValue(), "Xp", new NBTTagInt(0));
                    if (carried) {
                        merged++;
                    }
                }
            }
        }
        // Union: an on-disk entity the fresh capture does not have is carried forward, so a partial re-flush adds to
        // rather than overwrites the prior on-disk set. A keyed one is carried only when the fresh set lacks it,
        // matched against the whole fresh tree so an entity that moved into a vehicle is deduped, not duplicated.
        for (NBTBase element : diskEntities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound diskEntity = (NBTTagCompound) element;
            UUID uuid = readUuid(diskEntity);
            if (uuid == null || !freshUuids.contains(uuid)) {
                freshEntities.appendTag(diskEntity.copy());
                merged++;
            }
        }
        return merged;
    }

    static boolean hasCapturedContent(NBTTagCompound entity) {
        return NbtMerge.isNonEmptyList(entity, "Items") || hasCapturedOffers(entity);
    }

    private static boolean hasCapturedOffers(NBTTagCompound entity) {
        return entity.getTag("Offers") instanceof NBTTagCompound
                && NbtMerge.isNonEmptyList((NBTTagCompound) entity.getTag("Offers"), "Recipes");
    }

    static @Nullable UUID readUuid(NBTTagCompound entityTag) {
        return entityTag.hasUniqueId("UUID") ? entityTag.getUniqueId("UUID") : null;
    }
}
