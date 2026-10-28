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
import net.minecraft.world.level.ChunkPos;

/**
 * Every member is static, with no session, client or writer state of its own, which is what lets the call sites be
 * driven directly rather than only through a live capture session. That matters more here than the arithmetic each
 * member does: a merge has tests, and a call site that stops passing it an argument, wraps it in a condition, or stops
 * making it at all is neither a deletion nor a compile error, so nothing but a test of the call site itself sees it.
 */
final class ChunkFlushPlan {
    private ChunkFlushPlan() {}

    /**
     * Without the open-time positions a container captured this pass cannot be told from one never captured.
     */
    static RegionChunkWriter.ChunkReadMerge readMerge(Long2IntOpenHashMap bookshelfOccupancy,
            LongSet openTimeCaptured, LongSet replaced) {
        return (onDisk, fresh) -> ChunkMerge.merge(onDisk, fresh, bookshelfOccupancy, openTimeCaptured, replaced);
    }

    static RegionChunkWriter.ChunkReadMerge readMerge(ChunkSnapshotSource snapshot,
            List<BlockPos> landingContainers, LongSet replaced) {
        return readMerge(ChunkMerge.occupancyMap(), ChunkMerge.capturedPositions(landingContainers), replaced);
    }

    /**
     * The load-bearing property is that the result is a DETACHED copy: the per-dimension set it reads is live
     * main-thread state that the flush goes on to drain, and what is returned crosses to the writer thread.
     */
    static LongSet replacedIn(ChunkPos pos, LongSet replacedBlockKeys) {
        LongOpenHashSet inChunk = new LongOpenHashSet();
        for (long posKey : replacedBlockKeys) {
            if (new ChunkPos(BlockPos.method_10488(posKey)).equals(pos)) {
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

    static Set<ChunkPos> residualHolderChunks(Set<BlockPos> containerPositions,
            Set<BlockPos> lecternPositions) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos pos : containerPositions) {
            chunks.add(new ChunkPos(pos));
        }
        for (BlockPos pos : lecternPositions) {
            chunks.add(new ChunkPos(pos));
        }
        return chunks;
    }
}
