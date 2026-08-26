// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.IOException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The per-chunk discipline for region writes: a read, merge, or write that throws for one chunk is logged, that chunk
 * is skipped, and the save continues.
 */
final class RegionChunkWriter {
    private static final Logger LOGGER = LogManager.getLogger(RegionChunkWriter.class);

    private RegionChunkWriter() {}

    /**
     * Carry forward what the on-disk tag holds and the fresh capture lacks; returns the merge-back count. May mutate
     * {@code fresh} in place, and may throw while doing so: a throw abandons the write, so a partly-carried
     * {@code fresh} is never persisted. Mutations to {@code onDisk} are never written back.
     */
    @FunctionalInterface
    public interface ChunkReadMerge {
        int merge(NBTTagCompound onDisk, NBTTagCompound fresh);
    }

    /** Fold detached open-time holders into an on-disk chunk tag in place; returns how many block entities got them. */
    @FunctionalInterface
    public interface ChunkRewrite {
        int apply(NBTTagCompound onDisk);
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

    /** A read-merge write's outcome, plus the merge-back count the merge reported (zero unless re-captured). */
    static final class MergeWriteResult {
        private final MergeOutcome outcome;
        private final int mergeBacks;

        MergeWriteResult(MergeOutcome outcome, int mergeBacks) {
            this.outcome = outcome;
            this.mergeBacks = mergeBacks;
        }

        MergeOutcome outcome() {
            return outcome;
        }

        int mergeBacks() {
            return mergeBacks;
        }
    }

    /**
     * Runs on the writer thread, which solely owns {@code storage}. The prior may be a resumed download's or this
     * session's own earlier flush of the same chunk, so this is the ordinary revisit path and not only the resume path.
     * A {@code null} tag is {@link MergeOutcome#NOTHING_TO_WRITE}. A read that throws and a merge that throws both
     * leave what is stored untouched as {@link MergeOutcome#PRESERVED}, discarding the whole of {@code tag} rather than
     * only the part the merge would have added; a write that throws is {@link MergeOutcome#FAILED}.
     *
     * <p>At this band (era E3) entities live inside the region chunk under {@code Level.Entities}, which a fresh
     * terrain tag never carries, so a terrain re-write first carries forward the on-disk entity sibling that
     * {@link #foldEntitiesIntoRegion} folded there; a later entity fold in the same flush unions the fresh capture on
     * top. Without it a routine terrain re-flush of a revisited chunk, the default {@code EVERYWHERE} recapture mode,
     * would drop every entity a prior fold saved into that chunk.
     */
    public static MergeWriteResult writeMerging(WdlRegionStorage storage, ChunkPos pos,
            @Nullable NBTTagCompound tag, ChunkReadMerge merge) {
        if (tag == null) {
            return new MergeWriteResult(MergeOutcome.NOTHING_TO_WRITE, 0);
        }
        @Nullable
        NBTTagCompound onDisk;
        try {
            onDisk = storage.read(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("preserving chunk {}: on-disk read failed", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0);
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
                return new MergeWriteResult(MergeOutcome.PRESERVED, 0);
            }
            preserveEntities(onDisk, tag);
        }
        try {
            storage.write(pos, tag);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("skipping chunk {}: write failed", pos, e);
            return new MergeWriteResult(MergeOutcome.FAILED, 0);
        }
        return new MergeWriteResult(recaptured ? MergeOutcome.WRITTEN_RECAPTURED : MergeOutcome.WRITTEN_NEW,
                mergeBacks);
    }

    /**
     * Carry the on-disk chunk's {@code Level.Entities} into a fresh terrain tag that lacks it, so the entity sibling
     * that lives inside the region chunk at this band survives a terrain re-write. A no-op above the entity-fold band,
     * where the region chunk holds no entities, and on a chunk with none.
     */
    private static void preserveEntities(NBTTagCompound onDisk, NBTTagCompound fresh) {
        NBTTagCompound freshLevel = fresh.getCompoundTag("Level");
        if (freshLevel.getTag("Entities") instanceof NBTTagList) {
            return; // the fresh tag already carries entities; do not clobber them with the on-disk set
        }
        if (onDisk.getCompoundTag("Level").getTag("Entities") instanceof NBTTagList) {
            freshLevel.setTag("Entities", ((NBTTagList) onDisk.getCompoundTag("Level").getTag("Entities")).copy());
        }
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
    public static int rewriteExisting(WdlRegionStorage storage, ChunkPos pos, ChunkRewrite rewrite) {
        NBTTagCompound onDisk;
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
     * Fold this session's captured entities into {@code pos}'s already-written {@code region/} chunk under
     * {@code Level.Entities} (the pre-1.17 in-chunk entity location), unioning {@code freshEnvelope}'s entities into
     * what the chunk already holds through the shared {@link EntityMerge#merge}, then writing the chunk back. The host
     * chunk carries the terrain, so a missing host is a lost fold, never a terrainless chunk: with no on-disk prior the
     * captured entities are {@link MergeOutcome#FAILED}, logged and counted, rather than synthesized onto a chunk with
     * no {@code Sections}. Returns {@link MergeOutcome#WRITTEN_RECAPTURED} with the union count when the host already
     * held entities and {@link MergeOutcome#WRITTEN_NEW} otherwise, so the writer's entity tally reads as it did
     * against the standalone {@code entities/} store; a read, fold, or write failure isolates the chunk per the
     * per-chunk discipline, never aborting the drain.
     */
    public static MergeWriteResult foldEntitiesIntoRegion(WdlRegionStorage regionStorage, ChunkPos pos,
            NBTTagCompound freshEnvelope) {
        NBTTagCompound onDisk;
        try {
            onDisk = regionStorage.read(pos);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("preserving chunk {}: on-disk read failed before the entity fold", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0);
        }
        if (onDisk == null) {
            LOGGER.warn("chunk {}: no host chunk on disk; its captured entities are lost", pos);
            return new MergeWriteResult(MergeOutcome.FAILED, 0);
        }
        if (!(onDisk.getTag("Level") instanceof NBTTagCompound)) {
            // A malformed host with no Level compound cannot hold the fold; count it lost rather than storing the
            // host unchanged and reporting a clean write, the way the null-host branch above does.
            LOGGER.warn("chunk {}: on-disk chunk has no Level compound; its captured entities are lost", pos);
            return new MergeWriteResult(MergeOutcome.FAILED, 0);
        }
        NBTTagCompound level = onDisk.getCompoundTag("Level");
        NBTTagList diskEntities = level.getTag("Entities") instanceof NBTTagList
                ? (NBTTagList) level.getTag("Entities")
                : new NBTTagList();
        boolean recaptured = !diskEntities.isEmpty();
        int mergeBacks;
        try {
            NBTTagCompound onDiskEnvelope = new NBTTagCompound();
            onDiskEnvelope.setTag("Entities", diskEntities);
            mergeBacks = EntityMerge.merge(onDiskEnvelope, freshEnvelope);
        } catch (RuntimeException e) {
            LOGGER.warn("preserving chunk {}: entity fold failed", pos, e);
            return new MergeWriteResult(MergeOutcome.PRESERVED, 0);
        }
        if (freshEnvelope.getTag("Entities") instanceof NBTTagList) {
            level.setTag("Entities", freshEnvelope.getTag("Entities"));
        }
        try {
            regionStorage.write(pos, onDisk);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("skipping chunk {}: entity write-back failed", pos, e);
            return new MergeWriteResult(MergeOutcome.FAILED, 0);
        }
        return new MergeWriteResult(recaptured ? MergeOutcome.WRITTEN_RECAPTURED : MergeOutcome.WRITTEN_NEW,
                mergeBacks);
    }
}
