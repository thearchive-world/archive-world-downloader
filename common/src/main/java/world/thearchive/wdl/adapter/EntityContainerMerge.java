// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

final class EntityContainerMerge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EntityContainerMerge() {}

    /**
     * A container vehicle's contents reach the client only through its open menu, so every serialize after the first
     * fold is empty; a vehicle that has since moved to another entity-chunk, or crossed into another dimension, would
     * otherwise be written a second time holding nothing.
     *
     * <p>Deliberately not draining: the holder must stay available for any later chunk this vehicle reaches.
     */
    static MergeTally refoldFlushedContainers(ContainerSink sink, CompoundTag entitiesChunkTag,
            Map<UUID, CompoundTag> folded, Map<UUID, CompoundTag> stash) {
        if (folded.isEmpty() || !(entitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entityTag)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                CompoundTag holder = stash.containsKey(uuid) ? null : folded.get(uuid);
                if (holder == null) {
                    continue;
                }
                try {
                    foldItems(sink, node.getValue(), holder.copy());
                    merged++;
                } catch (RuntimeException e) {
                    failed++;
                    LOGGER.warn("skipping entity {}: container re-fold failed", uuid, e);
                }
            }
        }
        return new MergeTally(merged, failed);
    }

    static MergeTally mergeEntityStash(ContainerSink sink, CompoundTag entitiesChunkTag,
            Map<UUID, CompoundTag> stash) {
        if (stash.isEmpty() || !(entitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entityTag)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                CompoundTag holder = stash.remove(node.getKey());
                if (holder == null) {
                    continue;
                }
                try {
                    foldItems(sink, node.getValue(), holder);
                    merged++;
                } catch (RuntimeException e) {
                    failed++;
                    LOGGER.warn("skipping entity {}: container merge failed", node.getKey(), e);
                }
            }
        }
        return new MergeTally(merged, failed);
    }

    private static void foldItems(ContainerSink sink, CompoundTag entityTag, CompoundTag holder) {
        entityTag.put("Items", sink.merge(entityTag, holder).getList("Items", Tag.TAG_COMPOUND));
    }

    static MergeTally mergeMerchantStash(CompoundTag entitiesChunkTag, Map<UUID, CompoundTag> stash) {
        if (stash.isEmpty() || !(entitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entityTag)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                CompoundTag holder = stash.remove(node.getKey());
                if (holder == null) {
                    continue;
                }
                foldOffers(node.getValue(), holder);
                merged++;
            }
        }
        return new MergeTally(merged, 0);
    }

    static MergeTally refoldFlushedMerchants(CompoundTag entitiesChunkTag, Map<UUID, CompoundTag> folded,
            Map<UUID, CompoundTag> stash) {
        if (folded.isEmpty() || !(entitiesChunkTag.get("Entities") instanceof ListTag entities)) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entityTag)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                CompoundTag holder = stash.containsKey(uuid) ? null : folded.get(uuid);
                if (holder == null) {
                    continue;
                }
                foldOffers(node.getValue(), holder);
                merged++;
            }
        }
        return new MergeTally(merged, 0);
    }

    /**
     * Copy the holder's {@code "Offers"} onto the node, and its {@code "Xp"} when present (a wandering trader has
     * none).
     */
    private static void foldOffers(CompoundTag entityTag, CompoundTag holder) {
        if (holder.get("Offers") instanceof CompoundTag offers) {
            entityTag.put("Offers", offers.copy());
        }
        Tag experience = holder.get("Xp");
        if (experience != null) {
            entityTag.put("Xp", experience.copy());
        }
    }
}
