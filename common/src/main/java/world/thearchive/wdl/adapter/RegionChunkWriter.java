// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-chunk discipline for region writes: a read, merge, or write that throws an exception for one chunk is logged,
 * that chunk is skipped, and the save continues.
 */
final class RegionChunkWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionChunkWriter.class);

    private RegionChunkWriter() {}

    @FunctionalInterface
    public interface ChunkReadMerge {
        int merge(CompoundTag onDisk, CompoundTag fresh);
    }

    @FunctionalInterface
    public interface ChunkRewrite {
        int apply(CompoundTag tag);
    }

    /**
     * {@link #NOTHING_TO_WRITE} and {@link #PRESERVED} both leave the on-disk chunk as it was, and are split because
     * only a preserve is a loss: this session's capture of that chunk did not reach the save, which costs the archive
     * what a failed write costs it.
     */
    public enum MergeOutcome {
        WRITTEN_NEW,
        WRITTEN_RECAPTURED,
        NOTHING_TO_WRITE,
        PRESERVED,
        FAILED
    }

    record MergeWriteResult(MergeOutcome outcome, int mergeBacks, int recoveredCarries) {}

    /**
     * Runs on the writer thread, which solely owns {@code storage}.
     */
    public static MergeWriteResult writeMerging(IOWorker storage, ChunkPos pos,
            @Nullable CompoundTag tag, ChunkReadMerge merge) {
        return writeMerging(storage, pos, tag, merge, null);
    }

    /**
     * As above, plus {@code afterMerge}, run on the merged tag once the prior copy of this same chunk has had its say
     * and before anything is written. Ordering is the contract: a step run here can only see what the chunk's own prior
     * did not supply, so a carry sourced from another chunk can never displace this one's.
     */
    public static MergeWriteResult writeMerging(IOWorker storage, ChunkPos pos,
            @Nullable CompoundTag tag, ChunkReadMerge merge, @Nullable ChunkRewrite afterMerge) {
        if (tag == null) {
            return new MergeWriteResult(MergeOutcome.NOTHING_TO_WRITE, 0, 0);
        }
        @Nullable
        CompoundTag onDisk;
        try {
            onDisk = storage.load(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("preserving chunk {}: on-disk read failed", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
        }
        int mergeBacks = 0;
        int recoveredCarries = 0;
        boolean recaptured = onDisk != null;
        try {
            if (onDisk != null) {
                mergeBacks = merge.merge(onDisk, tag);
            }
            if (afterMerge != null) {
                recoveredCarries = afterMerge.apply(tag);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("preserving chunk {}: carry-forward merge failed", pos, e);
            // Falling through to the write here lands a tag whose carry-forward stopped at an unknown
            // point and reports it a clean re-captured write, which is a loss no term counts.
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
        }
        try {
            storage.store(pos, tag).join();
        } catch (RuntimeException e) {
            LOGGER.warn("skipping chunk {}: write failed", pos, e);
            return new MergeWriteResult(MergeOutcome.FAILED, 0, 0);
        }
        return new MergeWriteResult(recaptured ? MergeOutcome.WRITTEN_RECAPTURED : MergeOutcome.WRITTEN_NEW,
                mergeBacks, recoveredCarries);
    }

    /**
     * Returns how many block entities the fold landed contents on, and zero when there is no on-disk prior to fold into
     * (the contents are logged as lost rather than synthesized onto a terrainless chunk) or a read, fold, or write
     * failure isolates the chunk per the per-chunk discipline, never aborting the drain.
     */
    public static int rewriteExisting(IOWorker storage, ChunkPos pos, ChunkRewrite rewrite) {
        CompoundTag onDisk;
        try {
            onDisk = storage.load(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("orphaned-content merge for chunk {}: on-disk read failed; its captured contents are lost",
                    pos, e);
            return 0;
        }
        if (onDisk == null) {
            LOGGER.warn("orphaned-content merge for chunk {}: no on-disk prior; its captured contents are lost", pos);
            return 0;
        }
        int mergeBacks;
        try {
            mergeBacks = rewrite.apply(onDisk);
        } catch (RuntimeException e) {
            LOGGER.warn("orphaned-content merge for chunk {}: fold failed; its captured contents are lost", pos, e);
            return 0;
        }
        try {
            storage.store(pos, onDisk).join();
        } catch (RuntimeException e) {
            LOGGER.warn("orphaned-content merge for chunk {}: write-back failed", pos, e);
            return 0;
        }
        return mergeBacks;
    }
}
