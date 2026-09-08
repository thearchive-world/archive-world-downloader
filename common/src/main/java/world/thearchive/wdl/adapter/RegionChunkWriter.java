// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The per-chunk discipline for region writes: a read, merge, or write that throws for one chunk is logged, that chunk
 * is skipped, and the save continues.
 */
final class RegionChunkWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RegionChunkWriter() {}

    /**
     * Carry forward what the on-disk tag holds and the fresh capture lacks; returns the merge-back count. May mutate
     * {@code fresh} in place, and may throw while doing so: a throw abandons the write, so a partly-carried
     * {@code fresh} is never persisted. Mutations to {@code onDisk} are never written back.
     */
    @FunctionalInterface
    public interface ChunkReadMerge {
        int merge(CompoundTag onDisk, CompoundTag fresh);
    }

    /**
     * Mutate a chunk tag in place, returning how many nodes changed. May throw, and a throw abandons the write exactly
     * as a throwing merge does.
     */
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

    /**
     * A read-merge write's outcome, the merge-back count the merge reported (zero unless re-captured), and separately
     * the count an after-merge step carried in. The two are not summed: a merge-back came from this chunk's own prior
     * copy, an after-merge carry came from somewhere else, and only the second says a record moved between downloads.
     */
    record MergeWriteResult(MergeOutcome outcome, int mergeBacks, int recoveredCarries) {}

    /**
     * Runs on the writer thread, which solely owns {@code storage}. The prior may be a resumed download's or this
     * session's own earlier flush of the same chunk, so this is the ordinary revisit path and not only the resume path.
     * A {@code null} tag is {@link MergeOutcome#NOTHING_TO_WRITE}. A read that throws and a merge that throws both
     * leave what is stored untouched as {@link MergeOutcome#PRESERVED}, discarding the whole of {@code tag} rather than
     * only the part the merge would have added; a write that throws is {@link MergeOutcome#FAILED}.
     */
    public static MergeWriteResult writeMerging(IOWorker storage, ChunkPos pos,
            @Nullable CompoundTag tag, ChunkReadMerge merge) {
        return writeMerging(storage, pos, tag, merge, null);
    }

    /**
     * As above, plus {@code afterMerge}, run on the merged tag once the prior copy of this same chunk has had its say
     * and before anything is written. Ordering is the contract: a step run here can only see what the chunk's own prior
     * did not supply, so a carry sourced from another chunk can never displace this one's. A throw from it is the same
     * loss as a throwing merge and is preserved the same way, which is why it sits inside that try rather than beside
     * the write.
     */
    public static MergeWriteResult writeMerging(IOWorker storage, ChunkPos pos,
            @Nullable CompoundTag tag, ChunkReadMerge merge, @Nullable ChunkRewrite afterMerge) {
        if (tag == null) {
            return new MergeWriteResult(MergeOutcome.NOTHING_TO_WRITE, 0, 0);
        }
        @Nullable
        CompoundTag onDisk;
        try {
            Optional<CompoundTag> read = storage.loadAsync(pos).join();
            onDisk = read.orElse(null);
        } catch (RuntimeException e) {
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
     * Read an already-written chunk, fold detached open-time holders onto its block entities in place, and write it
     * back: the recovery path for a container or lectern opened in a chunk that had already flushed and left the
     * keep-hot buffer, so its captured contents never reached the on-disk block entity through the normal per-chunk
     * drain. The prior chunk carries the terrain and every already-saved container, so {@code rewrite} only adds the
     * newly-captured contents. Returns how many block entities the fold landed contents on, and zero when there is no
     * on-disk prior to fold into (the contents are logged as lost rather than synthesized onto a terrainless chunk) or
     * a read, fold, or write failure isolates the chunk per the per-chunk discipline, never aborting the drain.
     */
    public static int rewriteExisting(IOWorker storage, ChunkPos pos, ChunkRewrite rewrite) {
        CompoundTag onDisk;
        try {
            Optional<CompoundTag> read = storage.loadAsync(pos).join();
            onDisk = read.orElse(null);
        } catch (RuntimeException e) {
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
