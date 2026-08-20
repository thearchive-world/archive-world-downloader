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
import net.minecraft.world.level.dimension.DimensionType;

import world.thearchive.wdl.core.RecoveredCoverage;

/**
 * The resume recovered-coverage scan: on a resumed download the writer thread reads each prior on-disk chunk it carries
 * forward and this collects what a prior session already captured, so the outline marks it recovered rather than
 * unsaved (relaxed). Two inputs, both reusing the writer's own on-disk read so no second storage opens (the
 * single-owner hazard the design forbids): {@link #record} takes each region chunk (the {@link ChunkMerge} read-merge)
 * and collects the captured block positions, sharing {@link ChunkMerge#hasCapturedContent}; {@link #recordEntities}
 * reads that same region chunk's {@code Level.Entities} part (the {@link EntityMerge} read-merge) and collects the
 * captured container-entity UUIDs, sharing {@link EntityMerge#hasCapturedContent}. A coverage set tracks what its merge
 * preserves, and {@link #record} marks a position from its on-disk content alone, which leaves three edges on the block
 * side, each a position marked recovered whose next write does not carry it forward. A position whose block-entity type
 * changed since the prior session (a chest replaced by a barrel) is marked recovered even though {@link ChunkMerge}'s
 * id gate then carries nothing forward and writes it new-and-empty; the live {@code capturedBlockTypes} gate re-rims
 * that rare resume case. A container this session captures is written as the capture saw it rather than carried
 * forward, so a recovered container re-opened and found empty saves empty, and its rim comes from the capture, which
 * the outline ranks ahead of recovered. A position a block placement replaced carries nothing: a content-bearing
 * placement re-rims itself through its own optimistic captured mark, while an ordinary one does not, so a plain chest
 * built over a prior-saved container keeps the recovered rim its predecessor earned. The entity set has none of the
 * three.
 *
 * <p>An ender chest never enters the per-position coverage: its contents persist to player data, not the chunk block
 * entity, so {@code hasCapturedContent} cannot see them. Instead the shared ender inventory is a single global fact set
 * once at resume init by {@link #markEnderRecovered} when a prior download already saved it, and folded into every
 * published snapshot and the empty default so an ender chest reads no-rim on a resume that carries the inventory
 * forward, without the player reopening one.
 *
 * <p>Block coverage is per dimension, keyed by the dimension the writer is carrying forward: two containers at the same
 * coordinate in different dimensions must not cross-mark (the cross-dimension collision). Entity coverage is a single
 * flat set folded into each dimension's published snapshot, since a UUID is globally unique and cannot collide across
 * dimensions, the same split {@link RecoveredCoverage} draws. Threading: both {@link #record} and
 * {@link #recordEntities} run only on the writer thread and own the accumulators (the per-dimension block sets and the
 * flat entity set); each publishes a fresh immutable {@link RecoveredCoverage} snapshot into the concurrent
 * {@code coverage} map as one reference put, which the main thread reads through {@link #coverage(DimensionType)}. A
 * snapshot is taken only when a scan adds something new, so a resume's many empty chunks cost no copy.
 */
final class RecoveredScan {
    // The on-disk block-entity id of a chiseled bookshelf, matched verbatim off the saved tag rather than
    // through BlockEntityType (whose Mojmap holder is renamed across bands) so the scan stays raw-NBT and needs
    // no per-band plug. A bookshelf carries its prior-saved slots as a per-slot mask, kept out of the boolean
    // recovered set so a partially-saved shelf is not marked fully recovered.
    private static final String CHISELED_BOOKSHELF_ID = "minecraft:chiseled_bookshelf";
    private static final int BOOKSHELF_SLOTS = 6;

    private final Map<DimensionType, LongOpenHashSet> recoveredByDimension = new HashMap<>();
    private final Map<DimensionType, Long2IntOpenHashMap> bookshelfSlotsByDimension = new HashMap<>();
    private final Set<UUID> recoveredEntities = new HashSet<>();
    private final Map<DimensionType, RecoveredCoverage> coverage = new ConcurrentHashMap<>();

    // The one global cross-dimension ender fact, set once at resume init on the main thread, then read by the
    // writer thread in publish and by the main thread in coverage, so it is volatile. True means a prior download
    // already saved the shared player ender inventory and this resume carries it forward.
    private volatile boolean enderRecovered;

    /**
     * Writer thread: collect every prior-captured container position in {@code onDiskChunkTag} for {@code dimension}.
     */
    void record(DimensionType dimension, CompoundTag onDiskChunkTag) {
        if (!(onDiskChunkTag.getCompound("Level").get("TileEntities") instanceof ListTag)) {
            return;
        }
        ListTag blockEntities = (ListTag) onDiskChunkTag.getCompound("Level").get("TileEntities");
        LongOpenHashSet recovered = recoveredByDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet());
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.computeIfAbsent(dimension,
                key -> new Long2IntOpenHashMap());
        boolean grew = false;
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag blockEntity = blockEntities.get(i) instanceof CompoundTag
                    ? (CompoundTag) blockEntities.get(i)
                    : null;
            if (blockEntity == null || !hasCoordinates(blockEntity)) {
                continue;
            }
            long pos = new BlockPos(blockEntity.getInt("x"), blockEntity.getInt("y"),
                    blockEntity.getInt("z")).asLong();
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

    /**
     * Writer thread: collect every prior-captured container-entity UUID in {@code onDiskChunkTag}'s
     * {@code Level.Entities} (the 1.15.2 in-chunk entity location, sibling of the {@code Level.TileEntities} that
     * {@link #record} reads). Keyed by the dimension being scanned so the current dimension's snapshot is republished
     * with the new ids; the ids themselves are globally unique, so the accumulator behind them is flat.
     */
    void recordEntities(DimensionType dimension, CompoundTag onDiskChunkTag) {
        if (!(onDiskChunkTag.getCompound("Level").get("Entities") instanceof ListTag)) {
            return;
        }
        ListTag entities = (ListTag) onDiskChunkTag.getCompound("Level").get("Entities");
        boolean grew = false;
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag)) {
                continue;
            }
            CompoundTag entity = (CompoundTag) entities.get(i);
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

    /** Writer thread: swap a fresh immutable snapshot for {@code dimension}, folding in the flat entity ids. */
    private void publish(DimensionType dimension) {
        LongOpenHashSet recovered = recoveredByDimension.get(dimension);
        Long2IntOpenHashMap bookshelves = bookshelfSlotsByDimension.get(dimension);
        coverage.put(dimension, new RecoveredCoverage(
                recovered == null ? new LongOpenHashSet() : new LongOpenHashSet(recovered),
                bookshelves == null ? new Long2IntOpenHashMap() : new Long2IntOpenHashMap(bookshelves),
                new HashSet<>(recoveredEntities),
                enderRecovered));
    }

    /** The mask of occupied slots a chiseled bookshelf saved on disk (bit n = slot n) from its {@code "Items"}. */
    private static int bookshelfSavedSlotMask(CompoundTag blockEntity) {
        if (!(blockEntity.get("Items") instanceof ListTag)) {
            return 0;
        }
        ListTag items = (ListTag) blockEntity.get("Items");
        int mask = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag) {
                CompoundTag entry = (CompoundTag) items.get(i);
                // 0xFF default, not 0: a malformed entry with no Slot must drop on the range check below, not
                // phantom-mark slot 0. The range check also blocks a corrupt out-of-range Slot from 1 << 31.
                int slot = (entry.contains("Slot") ? entry.getByte("Slot") : (byte) 0xFF) & 0xFF;
                if (slot < BOOKSHELF_SLOTS) {
                    mask |= 1 << slot;
                }
            }
        }
        return mask;
    }

    /**
     * Resume init (main thread): mark the shared player ender inventory already saved by a prior download, so every
     * ender chest reads no-rim on this resume without the player reopening one. The gate that this is a RESUME with
     * savePlayerEnderChest on and a non-empty prior EnderItems lives at the call site, so the fact is never marked when
     * the resume would strip the inventory.
     */
    void markEnderRecovered() {
        enderRecovered = true;
    }

    /** Main thread: the latest published prior-session coverage for {@code dimension}, empty until one is recorded. */
    RecoveredCoverage coverage(DimensionType dimension) {
        return coverage.getOrDefault(dimension,
                enderRecovered ? RecoveredCoverage.ENDER_ONLY : RecoveredCoverage.EMPTY);
    }

    private static boolean hasCoordinates(CompoundTag blockEntity) {
        return blockEntity.get("x") instanceof IntTag && blockEntity.get("y") instanceof IntTag
                && blockEntity.get("z") instanceof IntTag;
    }
}
