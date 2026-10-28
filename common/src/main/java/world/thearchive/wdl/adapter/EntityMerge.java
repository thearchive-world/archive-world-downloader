// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.jspecify.annotations.Nullable;

final class EntityMerge {
    private EntityMerge() {}

    static int merge(CompoundTag onDisk, CompoundTag fresh) {
        if (!(fresh.get("Entities") instanceof ListTag) || !(onDisk.get("Entities") instanceof ListTag)) {
            return 0;
        }
        ListTag freshEntities = (ListTag) fresh.get("Entities");
        ListTag diskEntities = (ListTag) onDisk.get("Entities");
        Map<UUID, CompoundTag> diskNodes = new LinkedHashMap<>();
        for (int i = 0; i < diskEntities.size(); i++) {
            if (diskEntities.get(i) instanceof CompoundTag) {
                CompoundTag diskEntity = (CompoundTag) diskEntities.get(i);
                diskNodes.putAll(EntityTreeWalk.byUuid(diskEntity));
            }
        }
        Set<UUID> freshUuids = new HashSet<>();
        int merged = 0;
        for (int i = 0; i < freshEntities.size(); i++) {
            if (!(freshEntities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag freshEntity = (CompoundTag) freshEntities.get(i);
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(freshEntity).entrySet()) {
                freshUuids.add(node.getKey());
                CompoundTag diskNode = diskNodes.get(node.getKey());
                if (diskNode != null) {
                    // A version that writes Xp never syncs it, so there a client entity serializes Xp at the client
                    // zero.
                    boolean carried = NbtMerge.carryList(diskNode, node.getValue(), "Items");
                    carried |= NbtMerge.carryCompound(diskNode, node.getValue(), "Offers", null);
                    carried |= NbtMerge.carryValue(diskNode, node.getValue(), "Xp", new IntTag(0));
                    if (carried) {
                        merged++;
                    }
                }
            }
        }
        // Union: an on-disk entity the fresh capture does not have is carried forward, so a partial re-flush adds to
        // rather than overwrites the prior on-disk set. A keyed one is carried only when the fresh set lacks it,
        // matched against the whole fresh tree so an entity that moved into a vehicle is deduped, not duplicated.
        for (int i = 0; i < diskEntities.size(); i++) {
            if (!(diskEntities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag diskEntity = (CompoundTag) diskEntities.get(i);
            UUID uuid = readUuid(diskEntity);
            if (uuid == null || !freshUuids.contains(uuid)) {
                freshEntities.add(diskEntity.copy());
                merged++;
            }
        }
        return merged;
    }

    static boolean hasCapturedContent(CompoundTag entity) {
        return NbtMerge.isNonEmptyList(entity, "Items") || hasCapturedOffers(entity);
    }

    private static boolean hasCapturedOffers(CompoundTag entity) {
        return entity.get("Offers") instanceof CompoundTag
                && NbtMerge.isNonEmptyList((CompoundTag) entity.get("Offers"), "Recipes");
    }

    static @Nullable UUID readUuid(CompoundTag entityTag) {
        return entityTag.hasUUID("UUID") ? entityTag.getUUID("UUID") : null;
    }
}
