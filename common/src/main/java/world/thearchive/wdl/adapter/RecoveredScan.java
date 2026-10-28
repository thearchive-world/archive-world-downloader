// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import world.thearchive.wdl.core.RecoveredCoverage;

/**
 * {@link #record} and {@link #recordEntities} take tags the writer read through its own storage, so no second storage
 * opens (the single-owner hazard).
 *
 * <p>The shared ender inventory is a single global fact set once at resume init by {@link #markEnderRecovered} when a
 * prior download already saved it, and folded into every published snapshot and the empty default so an ender chest
 * reads no-rim on a resume that carries the inventory forward, without the player reopening one.
 *
 * <p>Block coverage is per dimension, keyed by the dimension the writer is carrying forward: two containers at the same
 * coordinate in different dimensions must not cross-mark (the cross-dimension collision). Threading: both
 * {@link #record} and {@link #recordEntities} run only on the writer thread and own the accumulators (the per-dimension
 * block sets and the flat entity set); each publishes a fresh immutable {@link RecoveredCoverage} snapshot into the
 * concurrent {@code coverage} map as one reference put, which the main thread reads through
 * {@link #coverage(ResourceKey)}.
 */
final class RecoveredScan {
    private static final String CHISELED_BOOKSHELF_ID = "minecraft:chiseled_bookshelf";
    private static final int BOOKSHELF_SLOTS = 6;

    private final Map<ResourceKey<Level>, LongOpenHashSet> recoveredByDimension = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2IntOpenHashMap> bookshelfSlotsByDimension = new HashMap<>();
    private final Set<UUID> recoveredEntities = new HashSet<>();
    private final Map<ResourceKey<Level>, RecoveredCoverage> coverage = new ConcurrentHashMap<>();

    private volatile boolean enderRecovered;

    void record(ResourceKey<Level> dimension, CompoundTag onDiskChunkTag) {
        if (!(onDiskChunkTag.get("block_entities") instanceof ListTag blockEntities)) {
            return;
        }
        LongOpenHashSet recovered = recoveredByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.computeIfAbsent(dimension,
                key -> new Long2IntOpenHashMap());
        boolean grew = false;
        for (int i = 0; i < blockEntities.size(); i++) {
            if (!(blockEntities.get(i) instanceof CompoundTag blockEntity) || !hasCoordinates(blockEntity)) {
                continue;
            }
            long pos = BlockPos.asLong(blockEntity.getInt("x"), blockEntity.getInt("y"),
                    blockEntity.getInt("z"));
            if (CHISELED_BOOKSHELF_ID.equals(blockEntity.getString("id"))) {
                int mask = bookshelfSavedSlotMask(blockEntity);
                if (mask != 0 && bookshelves.put(pos, mask) != mask) {
                    grew = true;
                }
            } else if (ChunkMerge.hasCapturedContent(blockEntity)) {
                grew |= recovered.add(pos);
            }
        }
        if (grew) {
            publish(dimension);
        }
    }

    void recordEntities(ResourceKey<Level> dimension, CompoundTag onDiskEntityChunkTag) {
        if (!(onDiskEntityChunkTag.get("Entities") instanceof ListTag entities)) {
            return;
        }
        boolean grew = false;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entity)) {
                continue;
            }
            for (Map.Entry<UUID, CompoundTag> node : EntityTreeWalk.byUuid(entity).entrySet()) {
                if (EntityMerge.hasCapturedContent(node.getValue())) {
                    grew |= recoveredEntities.add(node.getKey());
                }
            }
        }
        if (grew) {
            publish(dimension);
        }
    }

    private void publish(ResourceKey<Level> dimension) {
        LongOpenHashSet recovered = recoveredByDimension.get(dimension);
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.get(dimension);
        coverage.put(dimension, new RecoveredCoverage(
                recovered == null ? new LongOpenHashSet() : new LongOpenHashSet(recovered),
                bookshelves == null ? new Long2IntOpenHashMap() : new Long2IntOpenHashMap(bookshelves),
                new HashSet<>(recoveredEntities),
                enderRecovered));
    }

    private static int bookshelfSavedSlotMask(CompoundTag blockEntity) {
        if (!(blockEntity.get("Items") instanceof ListTag items)) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag entry) {
                int slot = (entry.contains("Slot") ? entry.getByte("Slot") : (byte) 0xFF) & 0xFF;
                if (slot < BOOKSHELF_SLOTS) {
                    mask |= 1 << slot;
                }
            }
        }
        return mask;
    }

    void markEnderRecovered() {
        enderRecovered = true;
    }

    RecoveredCoverage coverage(ResourceKey<Level> dimension) {
        return coverage.getOrDefault(dimension,
                enderRecovered ? RecoveredCoverage.ENDER_ONLY : RecoveredCoverage.EMPTY);
    }

    private static boolean hasCoordinates(CompoundTag blockEntity) {
        return blockEntity.get("x") instanceof IntTag && blockEntity.get("y") instanceof IntTag
                && blockEntity.get("z") instanceof IntTag;
    }
}
