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
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;

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
 * {@link #coverage(DimensionType)}.
 */
final class RecoveredScan {
    private static final String CHISELED_BOOKSHELF_ID = "minecraft:chiseled_bookshelf";
    private static final int BOOKSHELF_SLOTS = 6;

    private final Map<DimensionType, LongOpenHashSet> recoveredByDimension = new HashMap<>();
    private final Map<DimensionType, Long2IntOpenHashMap> bookshelfSlotsByDimension = new HashMap<>();
    private final Set<UUID> recoveredEntities = new HashSet<>();
    private final Map<DimensionType, RecoveredCoverage> coverage = new ConcurrentHashMap<>();

    private volatile boolean enderRecovered;

    void record(DimensionType dimension, NBTTagCompound onDiskChunkTag) {
        if (!(onDiskChunkTag.getCompoundTag("Level").getTag("TileEntities") instanceof NBTTagList)) {
            return;
        }
        NBTTagList blockEntities = (NBTTagList) onDiskChunkTag.getCompoundTag("Level").getTag("TileEntities");
        LongOpenHashSet recovered = recoveredByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.computeIfAbsent(dimension,
                key -> new Long2IntOpenHashMap());
        boolean grew = false;
        for (NBTBase element : blockEntities) {
            NBTTagCompound blockEntity = element instanceof NBTTagCompound ? (NBTTagCompound) element : null;
            if (blockEntity == null || !hasCoordinates(blockEntity)) {
                continue;
            }
            long pos = new BlockPos(blockEntity.getInteger("x"), blockEntity.getInteger("y"),
                    blockEntity.getInteger("z")).toLong();
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

    void recordEntities(DimensionType dimension, NBTTagCompound onDiskChunkTag) {
        if (!(onDiskChunkTag.getCompoundTag("Level").getTag("Entities") instanceof NBTTagList)) {
            return;
        }
        NBTTagList entities = (NBTTagList) onDiskChunkTag.getCompoundTag("Level").getTag("Entities");
        boolean grew = false;
        for (NBTBase element : entities) {
            if (!(element instanceof NBTTagCompound)) {
                continue;
            }
            NBTTagCompound entity = (NBTTagCompound) element;
            for (Map.Entry<UUID, NBTTagCompound> node : EntityTreeWalk.byUuid(entity).entrySet()) {
                if (EntityMerge.hasCapturedContent(node.getValue())) {
                    grew |= recoveredEntities.add(node.getKey());
                }
            }
        }
        if (grew) {
            publish(dimension);
        }
    }

    private void publish(DimensionType dimension) {
        LongOpenHashSet recovered = recoveredByDimension.get(dimension);
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.get(dimension);
        coverage.put(dimension, new RecoveredCoverage(
                recovered == null ? new LongOpenHashSet() : new LongOpenHashSet(recovered),
                bookshelves == null ? new Long2IntOpenHashMap() : new Long2IntOpenHashMap(bookshelves),
                new HashSet<>(recoveredEntities),
                enderRecovered));
    }

    private static int bookshelfSavedSlotMask(NBTTagCompound blockEntity) {
        if (!(blockEntity.getTag("Items") instanceof NBTTagList)) {
            return 0;
        }
        NBTTagList items = (NBTTagList) blockEntity.getTag("Items");
        int mask = 0;
        for (NBTBase element : items) {
            if (element instanceof NBTTagCompound) {
                NBTTagCompound entry = (NBTTagCompound) element;
                int slot = (entry.hasKey("Slot") ? entry.getByte("Slot") : (byte) 0xFF) & 0xFF;
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

    RecoveredCoverage coverage(DimensionType dimension) {
        return coverage.getOrDefault(dimension,
                enderRecovered ? RecoveredCoverage.ENDER_ONLY : RecoveredCoverage.EMPTY);
    }

    private static boolean hasCoordinates(NBTTagCompound blockEntity) {
        return blockEntity.getTag("x") instanceof NBTTagInt && blockEntity.getTag("y") instanceof NBTTagInt
                && blockEntity.getTag("z") instanceof NBTTagInt;
    }
}
