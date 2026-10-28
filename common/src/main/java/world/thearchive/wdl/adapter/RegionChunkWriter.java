// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The per-chunk discipline for region writes: a read, merge, or write that throws an exception for one chunk is logged,
 * that chunk is skipped, and the save continues.
 */
final class RegionChunkWriter {
    private static final Logger LOGGER = LogManager.getLogger(RegionChunkWriter.class);

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

    static final class MergeWriteResult {
        private final MergeOutcome outcome;
        private final int mergeBacks;
        private final int recoveredCarries;

        MergeWriteResult(MergeOutcome outcome, int mergeBacks, int recoveredCarries) {
            this.outcome = outcome;
            this.mergeBacks = mergeBacks;
            this.recoveredCarries = recoveredCarries;
        }

        MergeOutcome outcome() {
            return outcome;
        }

        int mergeBacks() {
            return mergeBacks;
        }

        int recoveredCarries() {
            return recoveredCarries;
        }
    }

    /**
     * Runs on the writer thread, which solely owns {@code storage}.
     *
     * <p>At this band entities live inside the region chunk under {@code Level.Entities}, which a fresh terrain tag
     * never carries, so a terrain re-write first carries forward the on-disk entity sibling that
     * {@link #foldEntitiesIntoRegion} folded there. Without it a routine terrain re-flush of a revisited chunk, the
     * default {@code EVERYWHERE} recapture mode, would drop every entity a prior fold saved into that chunk.
     */
    public static MergeWriteResult writeMerging(WdlRegionStorage storage, ChunkPos pos,
            @Nullable CompoundTag tag, ChunkReadMerge merge) {
        if (tag == null) {
            return new MergeWriteResult(MergeOutcome.NOTHING_TO_WRITE, 0, 0);
        }
        @Nullable
        CompoundTag onDisk;
        try {
            onDisk = storage.read(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("preserving chunk {}: on-disk read failed", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
        }
        int mergeBacks = 0;
        boolean recaptured = onDisk != null;
        if (onDisk != null) {
            try {
                mergeBacks = merge.merge(onDisk, tag);
            } catch (RuntimeException e) {
                LOGGER.warn("preserving chunk {}: carry-forward merge failed", pos, e);
                // Falling through to the write here lands a tag whose carry-forward stopped at an unknown
                // point and reports it a clean re-captured write, which is a loss no term counts.
                return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
            }
            preserveEntities(onDisk, tag);
        }
        try {
            storage.write(pos, tag);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("skipping chunk {}: write failed", pos, e);
            return new MergeWriteResult(MergeOutcome.FAILED, 0, 0);
        }
        return new MergeWriteResult(recaptured ? MergeOutcome.WRITTEN_RECAPTURED : MergeOutcome.WRITTEN_NEW,
                mergeBacks, 0);
    }

    private static void preserveEntities(CompoundTag onDisk, CompoundTag fresh) {
        CompoundTag freshLevel = fresh.getCompound("Level");
        if (freshLevel.get("Entities") instanceof ListTag) {
            return;
        }
        if (onDisk.getCompound("Level").get("Entities") instanceof ListTag) {
            freshLevel.put("Entities", ((ListTag) onDisk.getCompound("Level").get("Entities")).copy());
        }
    }

    /**
     * Returns how many block entities the fold landed contents on, and zero when there is no on-disk prior to fold into
     * (the contents are logged as lost rather than synthesized onto a terrainless chunk) or a read, fold, or write
     * failure isolates the chunk per the per-chunk discipline, never aborting the drain.
     */
    public static int rewriteExisting(WdlRegionStorage storage, ChunkPos pos, ChunkRewrite rewrite) {
        CompoundTag onDisk;
        try {
            onDisk = storage.read(pos);
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
            storage.write(pos, onDisk);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("orphaned-content merge for chunk {}: write-back failed", pos, e);
            return 0;
        }
        return mergeBacks;
    }

    /**
     * A missing host is a lost fold, never a terrainless chunk: with no on-disk prior the captured entities are
     * {@link MergeOutcome#FAILED}, logged and counted, rather than synthesized onto a chunk with no {@code Sections}.
     */
    public static MergeWriteResult foldEntitiesIntoRegion(WdlRegionStorage regionStorage, ChunkPos pos,
            CompoundTag freshEnvelope, @Nullable ChunkRewrite afterMerge) {
        CompoundTag onDisk;
        try {
            onDisk = regionStorage.read(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("preserving chunk {}: on-disk read failed before the entity fold", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
        }
        if (onDisk == null) {
            LOGGER.warn("chunk {}: no host chunk on disk; its captured entities are lost", pos);
            return new MergeWriteResult(MergeOutcome.FAILED, 0, 0);
        }
        if (!(onDisk.get("Level") instanceof CompoundTag)) {
            // A malformed host with no Level compound cannot hold the fold; count it lost rather than storing the host
            // unchanged and reporting a clean write.
            LOGGER.warn("chunk {}: on-disk chunk has no Level compound; its captured entities are lost", pos);
            return new MergeWriteResult(MergeOutcome.FAILED, 0, 0);
        }
        CompoundTag level = onDisk.getCompound("Level");
        ListTag diskEntities = level.get("Entities") instanceof ListTag
                ? (ListTag) level.get("Entities")
                : new ListTag();
        boolean recaptured = !diskEntities.isEmpty();
        int mergeBacks;
        int recoveredCarries = 0;
        try {
            CompoundTag onDiskEnvelope = new CompoundTag();
            onDiskEnvelope.put("Entities", diskEntities);
            mergeBacks = EntityMerge.merge(onDiskEnvelope, freshEnvelope);
            if (afterMerge != null) {
                recoveredCarries = afterMerge.apply(freshEnvelope);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("preserving chunk {}: entity fold failed", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0, 0);
        }
        if (freshEnvelope.get("Entities") instanceof ListTag) {
            level.put("Entities", freshEnvelope.get("Entities"));
        }
        try {
            regionStorage.write(pos, onDisk);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("skipping chunk {}: entity write-back failed", pos, e);
            return new MergeWriteResult(MergeOutcome.FAILED, 0, 0);
        }
        return new MergeWriteResult(recaptured ? MergeOutcome.WRITTEN_RECAPTURED : MergeOutcome.WRITTEN_NEW,
                mergeBacks, recoveredCarries);
    }
}
