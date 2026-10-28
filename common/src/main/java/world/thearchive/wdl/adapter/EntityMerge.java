// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.jspecify.annotations.Nullable;

final class EntityMerge {
    private EntityMerge() {}

    static int merge(CompoundTag onDisk, CompoundTag fresh) {
        if (!(fresh.get("Entities") instanceof ListTag freshEntities)
                || !(onDisk.get("Entities") instanceof ListTag diskEntities)) {
            return 0;
        }
        Map<UUID, CompoundTag> diskNodes = new LinkedHashMap<>();
        for (int i = 0; i < diskEntities.size(); i++) {
            if (diskEntities.get(i) instanceof CompoundTag diskEntity) {
                diskNodes.putAll(EntityTreeWalk.byUuid(diskEntity));
            }
        }
        Set<UUID> freshUuids = new HashSet<>();
        int merged = 0;
        for (int i = 0; i < freshEntities.size(); i++) {
            if (!(freshEntities.get(i) instanceof CompoundTag freshEntity)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(freshEntity).entrySet()) {
                freshUuids.add(node.getKey());
                CompoundTag diskNode = diskNodes.get(node.getKey());
                if (diskNode != null) {
                    // A version that writes Xp never syncs it, so there a client entity serializes Xp at the client
                    // zero.
                    boolean carried = NbtMerge.carryList(diskNode, node.getValue(), "Items");
                    carried |= NbtMerge.carryCompound(diskNode, node.getValue(), "Offers", null);
                    carried |= NbtMerge.carryValue(diskNode, node.getValue(), "Xp", IntTag.valueOf(0));
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
            if (!(diskEntities.get(i) instanceof CompoundTag diskEntity)) {
                continue;
            }
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
        return entity.get("Offers") instanceof CompoundTag offers && NbtMerge.isNonEmptyList(offers, "Recipes");
    }

    static @Nullable UUID readUuid(CompoundTag entityTag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, entityTag.get("UUID")).result().orElse(null);
    }
}
