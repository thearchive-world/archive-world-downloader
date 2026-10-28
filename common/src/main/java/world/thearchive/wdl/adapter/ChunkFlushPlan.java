// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every member is static, with no session, client or writer state of its own, which is what lets the call sites be
 * driven directly rather than only through a live capture session. That matters more here than the arithmetic each
 * member does: a merge has tests, and a call site that stops passing it an argument, wraps it in a condition, or stops
 * making it at all is neither a deletion nor a compile error, so nothing but a test of the call site itself sees it.
 */
final class ChunkFlushPlan {
    private ChunkFlushPlan() {}

    /**
     * Without the occupancy every on-disk bookshelf slot carries back regardless of the block-state, and without the
     * open-time positions a container captured this pass cannot be told from one never captured.
     */
    static RegionChunkWriter.ChunkReadMerge readMerge(Long2IntOpenHashMap bookshelfOccupancy,
            LongSet openTimeCaptured, LongSet replaced) {
        return (onDisk, fresh) -> ChunkMerge.merge(onDisk, fresh, bookshelfOccupancy, openTimeCaptured, replaced);
    }

    /**
     * Reading the occupancy under a condition, or handing over an empty one, writes books into slots the chunk's own
     * saved block-state reports empty, and neither is a deletion nor a compile error.
     */
    static RegionChunkWriter.ChunkReadMerge readMerge(ChunkSnapshotSource snapshot,
            List<BlockPos> landingContainers, LongSet replaced) {
        return readMerge(bookshelfOccupancy(snapshot), ChunkMerge.capturedPositions(landingContainers), replaced);
    }

    /**
     * The load-bearing property is that the result is a DETACHED copy: the per-dimension set it reads is live
     * main-thread state that the flush goes on to drain, and what is returned crosses to the writer thread.
     */
    static LongSet replacedIn(ChunkPos pos, LongSet replacedBlockKeys) {
        LongOpenHashSet inChunk = new LongOpenHashSet();
        for (long posKey : replacedBlockKeys) {
            if (ChunkPos.containing(BlockPos.of(posKey)).equals(pos)) {
                inChunk.add(posKey);
            }
        }
        return inChunk;
    }

    static MergeTally foldChunkStashes(CompoundTag tag, ChunkPos pos, ContainerSink containerSink,
            LecternSink lecternSink, Map<BlockPos, CompoundTag> containers,
            Map<BlockPos, CompoundTag> lecterns, Map<BlockPos, CompoundTag> holders) {
        MergeTally items = ContainerMerge.mergeChunkStash(containerSink, tag, pos, containers);
        MergeTally books = ContainerMerge.mergeLecternChunkStash(lecternSink, tag, pos, lecterns);
        MergeTally fields = ContainerMerge.mergeHolderChunkStash(tag, pos, holders);
        return sum(items, books, fields);
    }

    static MergeTally foldResidualHolders(CompoundTag onDisk, ChunkPos pos, ContainerSink containerSink,
            LecternSink lecternSink, Map<BlockPos, CompoundTag> containers,
            Map<BlockPos, CompoundTag> lecterns) {
        MergeTally items = ContainerMerge.mergeChunkStash(containerSink, onDisk, pos, containers);
        MergeTally books = ContainerMerge.mergeLecternChunkStash(lecternSink, onDisk, pos, lecterns);
        return sum(items, books);
    }

    private static MergeTally sum(MergeTally... tallies) {
        int merged = 0;
        int failed = 0;
        for (MergeTally tally : tallies) {
            merged += tally.merged();
            failed += tally.failed();
        }
        return new MergeTally(merged, failed);
    }

    /**
     * The positions whose holder will actually land on a captured block entity, which is the same match the writer-side
     * fold re-makes: the position exists in the snapshot and the recorded block-entity type still agrees. Built from
     * that rather than from the drained bundle's keys, because a holder the fold drops writes nothing, and telling the
     * carry-forward it was captured there would blank what an earlier visit saved.
     */
    static List<BlockPos> landingHolderPositions(ChunkSnapshotSource snapshot,
            Map<BlockPos, CompoundTag> holders) {
        List<BlockPos> landing = new ArrayList<>();
        for (Map.Entry<BlockPos, CompoundTag> holder : holders.entrySet()) {
            for (CompoundTag blockEntity : snapshot.blockEntities()) {
                if (NbtMerge.isBlockEntityAt(blockEntity, holder.getKey())
                        && ContainerMerge.recordedTypeMatches(holder.getValue(), blockEntity)) {
                    landing.add(holder.getKey());
                    break;
                }
            }
        }
        return landing;
    }

    /**
     * Built unconditionally, including when container capture is off: this mask re-gates what an EARLIER session wrote,
     * not what this one captured, and an empty map reads downstream as occupancy unknown, which carries every on-disk
     * slot back. Skipping it would write books into slots the same chunk's saved block-state reports empty.
     */
    static Long2IntOpenHashMap bookshelfOccupancy(ChunkSnapshotSource snapshot) {
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        for (CompoundTag blockEntity : snapshot.blockEntities()) {
            // Filter on the saved id before resolving a block state: that resolve walks the snapshot's sections
            // to find the one covering the position, so doing it for every block entity of every flushed chunk
            // would put a scan per block entity on the main thread to answer a question only a bookshelf asks.
            if (!ChunkMerge.CHISELED_BOOKSHELF_ID.equals(blockEntity.getStringOr("id", ""))
                    || !(blockEntity.get("x") instanceof IntTag x)
                    || !(blockEntity.get("y") instanceof IntTag y)
                    || !(blockEntity.get("z") instanceof IntTag z)) {
                continue;
            }
            BlockPos pos = new BlockPos(x.intValue(), y.intValue(), z.intValue());
            BlockState state = InteractionCapture.blockStateAt(snapshot, pos);
            if (state != null && state.getBlock() instanceof ChiseledBookShelfBlock) {
                occupancy.put(pos.asLong(), BookshelfSlots.occupiedSlotMask(state));
            }
        }
        return occupancy;
    }

    static Set<ChunkPos> residualHolderChunks(Set<BlockPos> containerPositions,
            Set<BlockPos> lecternPositions) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos pos : containerPositions) {
            chunks.add(ChunkPos.containing(pos));
        }
        for (BlockPos pos : lecternPositions) {
            chunks.add(ChunkPos.containing(pos));
        }
        return chunks;
    }
}
