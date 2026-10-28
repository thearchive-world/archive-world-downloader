// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class EntityContainerMerge {
    private static final Logger LOGGER = LogManager.getLogger(EntityContainerMerge.class);

    private EntityContainerMerge() {}

    /**
     * A container vehicle's contents reach the client only through its open menu, so every serialize after the first
     * fold is empty; a vehicle that has since moved to another entity-chunk, or crossed into another dimension, would
     * otherwise be written a second time holding nothing.
     *
     * <p>Deliberately not draining: the holder must stay available for any later chunk this vehicle reaches.
     */
    static MergeTally refoldFlushedContainers(ContainerSink sink, NBTTagCompound entitiesChunkTag,
            Map<UUID, NBTTagCompound> folded, Map<UUID, NBTTagCompound> stash) {
        NBTTagList entities = entitiesChunkTag.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) entitiesChunkTag.getTag("Entities")
                : null;
        if (folded.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (NBTBase element : entities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entityTag = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                NBTTagCompound holder = stash.containsKey(uuid) ? null : folded.get(uuid);
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

    static MergeTally mergeEntityStash(ContainerSink sink, NBTTagCompound entitiesChunkTag,
            Map<UUID, NBTTagCompound> stash) {
        NBTTagList entities = entitiesChunkTag.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) entitiesChunkTag.getTag("Entities")
                : null;
        if (stash.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        int failed = 0;
        for (NBTBase element : entities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entityTag = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                NBTTagCompound holder = stash.remove(node.getKey());
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

    private static void foldItems(ContainerSink sink, NBTTagCompound entityTag, NBTTagCompound holder) {
        entityTag.setTag("Items", sink.merge(entityTag, holder).getTagList("Items", 10));
    }

    static MergeTally mergeMerchantStash(NBTTagCompound entitiesChunkTag, Map<UUID, NBTTagCompound> stash) {
        NBTTagList entities = entitiesChunkTag.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) entitiesChunkTag.getTag("Entities")
                : null;
        if (stash.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (NBTBase element : entities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entityTag = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                NBTTagCompound holder = stash.remove(node.getKey());
                if (holder == null) {
                    continue;
                }
                foldOffers(node.getValue(), holder);
                merged++;
            }
        }
        return new MergeTally(merged, 0);
    }

    static MergeTally refoldFlushedMerchants(NBTTagCompound entitiesChunkTag, Map<UUID, NBTTagCompound> folded,
            Map<UUID, NBTTagCompound> stash) {
        NBTTagList entities = entitiesChunkTag.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) entitiesChunkTag.getTag("Entities")
                : null;
        if (folded.isEmpty() || entities == null) {
            return new MergeTally(0, 0);
        }
        int merged = 0;
        for (NBTBase element : entities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entityTag = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entityTag).entrySet()) {
                UUID uuid = node.getKey();
                NBTTagCompound holder = stash.containsKey(uuid) ? null : folded.get(uuid);
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
     * Copy the holder's {@code "Offers"} onto the node, and its {@code "Xp"} when present (trade experience is a 1.14
     * addition, so no holder below it carries one).
     */
    private static void foldOffers(NBTTagCompound entityTag, NBTTagCompound holder) {
        if (holder.getTag("Offers") instanceof NBTTagCompound) {
            NBTTagCompound offers = (NBTTagCompound) holder.getTag("Offers");
            entityTag.setTag("Offers", offers.copy());
        }
        NBTBase experience = holder.getTag("Xp");
        if (experience != null) {
            entityTag.setTag("Xp", experience.copy());
        }
    }
}
