// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.SaveProgress;

/**
 * A single background thread that owns the captured world's region storages and drains submitted chunks to disk, so the
 * client render thread never blocks on region I/O and never runs the heavy chunk serialize either. {@link #submitChunk}
 * hands over a lazy encode-and-fold {@link Supplier} that the writer thread resolves (the deferred but deterministic
 * encode of immutable, detached inputs); {@link #submitEntity} hands over a fully-encoded, immutable
 * {@link CompoundTag}. Either way the writer thread is the sole owner of the {@link WdlRegionStorage}s, the one-writer
 * invariant, and the reason only immutable or deferred-immutable work may cross the queue.
 *
 * <p>At this band the store itself needs the single thread: vanilla {@code RegionFileStorage} keeps an unsynchronized
 * region-file cache and does synchronous I/O, so it is not safe to call from more than one thread. The mod's own paths
 * need it too. The per-dimension {@link Storages} are unsynchronized and open lazily by get-then-put, so a second
 * writer could open two storages over one directory. The merge, rewrite and entity-fold paths are read-modify-write per
 * {@link ChunkPos} and would lose a merge if interleaved. And the finalize order is fixed.
 *
 * <p>{@link #finish()} enqueues an end-of-stream marker; the writer drains the remaining tags, closes each storage
 * (which is what flushes its region files, there being no channel force at this band), runs the {@link Finalizer} (the
 * band's level.dat write), and completes the returned future with the per-target tallies (or the error that aborted
 * it). At 1.14.4 the vanilla {@code LevelStorage} holds no OS lock to release at finish (session.lock is advisory
 * pre-1.16), so there is nothing to close here. The future is completed normally even on failure, so the caller polls
 * it with one branch.
 */
final class AsyncSaveWriter {
    private static final Logger LOGGER = LogManager.getLogger(AsyncSaveWriter.class);

    /**
     * Lazily opens a region storage on the writer thread; called once per (target, dimension) on its first tag. The
     * dimension lets one writer follow the player across a portal, opening each dimension's storage on demand, so two
     * same-position chunks in different dimensions never collide on one storage.
     */
    @FunctionalInterface
    public interface StorageOpener {
        WdlRegionStorage open(DimensionType dimension) throws Exception;
    }

    /** The best-effort pre-write step run before the drain opens any storage (the resume backup). */
    @FunctionalInterface
    public interface Preflight {
        void run() throws Exception;
    }

    /**
     * The on-disk step run on the writer thread after the storages are drained and closed (level.dat, then idcounts and
     * the completion record). It receives the writer's soft-failure tallies so the completion record can stamp the real
     * clean-or-partial status; both are zero on a loss-free save.
     */
    @FunctionalInterface
    public interface Finalizer {
        void run(int chunksFailed, int entityChunksFailed) throws Exception;
    }

    /**
     * The best-effort finish-time output step run after the storages have closed (the export zip). Returns the written
     * zip's filename for the completion surfaces, or null when none was written.
     */
    @FunctionalInterface
    public interface OutputFinalizer {
        @Nullable
        String run() throws Exception;
    }

    /**
     * Per-target write tallies, plus any error that aborted the save (null on success). The region chunks are split
     * into those new to the archive (no prior on disk) and those re-captured (merged with a prior copy on a resume),
     * with {@code mergedContainers} the count of on-disk block containers whose contents carried forward;
     * {@code entitiesCarriedForward} is the entity analog (a re-flushed entity-chunk's contents and unioned-in
     * entities), a diagnostic that flags chunks re-flushed with partial sets. {@link #chunksWritten()} is the total the
     * saved-world message reports. {@code zipFileName} is the export zip actually written on a clean save, or null
     * (knob off, zip failed, or the save failed), so a completion surface never names a zip that is not on disk.
     */
    static final class SaveResult {
        private final int chunksNew;
        private final int chunksRecaptured;
        private final int mergedContainers;
        private final int chunksFailed;
        private final int entityChunksWritten;
        private final int entityChunksFailed;
        private final int entitiesCarriedForward;
        private final @Nullable String zipFileName;
        private final @Nullable Throwable error;

        SaveResult(int chunksNew, int chunksRecaptured, int mergedContainers, int chunksFailed,
                int entityChunksWritten, int entityChunksFailed, int entitiesCarriedForward,
                @Nullable String zipFileName, @Nullable Throwable error) {
            this.chunksNew = chunksNew;
            this.chunksRecaptured = chunksRecaptured;
            this.mergedContainers = mergedContainers;
            this.chunksFailed = chunksFailed;
            this.entityChunksWritten = entityChunksWritten;
            this.entityChunksFailed = entityChunksFailed;
            this.entitiesCarriedForward = entitiesCarriedForward;
            this.zipFileName = zipFileName;
            this.error = error;
        }

        int chunksNew() {
            return chunksNew;
        }

        int chunksRecaptured() {
            return chunksRecaptured;
        }

        int mergedContainers() {
            return mergedContainers;
        }

        int chunksFailed() {
            return chunksFailed;
        }

        int entityChunksWritten() {
            return entityChunksWritten;
        }

        int entityChunksFailed() {
            return entityChunksFailed;
        }

        int entitiesCarriedForward() {
            return entitiesCarriedForward;
        }

        @Nullable
        String zipFileName() {
            return zipFileName;
        }

        @Nullable
        Throwable error() {
            return error;
        }

        public boolean failed() {
            return error != null;
        }

        /** Region chunks written this session, new plus re-captured (the archive headline minus the prior count). */
        public int chunksWritten() {
            return chunksNew + chunksRecaptured;
        }
    }

    private enum Target {
        REGION,
        ENTITIES
    }

    private interface Task {}

    private static final class WriteTask implements Task {
        private final Target target;
        private final DimensionType dimension;
        private final ChunkPos pos;
        private final Supplier<CompoundTag> encode;
        private final RegionChunkWriter.ChunkReadMerge merge;

        WriteTask(Target target, DimensionType dimension, ChunkPos pos, Supplier<CompoundTag> encode,
                RegionChunkWriter.ChunkReadMerge merge) {
            this.target = target;
            this.dimension = dimension;
            this.pos = pos;
            this.encode = encode;
            this.merge = merge;
        }

        Target target() {
            return target;
        }

        DimensionType dimension() {
            return dimension;
        }

        ChunkPos pos() {
            return pos;
        }

        Supplier<CompoundTag> encode() {
            return encode;
        }

        RegionChunkWriter.ChunkReadMerge merge() {
            return merge;
        }
    }

    private static final class RewriteTask implements Task {
        private final DimensionType dimension;
        private final ChunkPos pos;
        private final RegionChunkWriter.ChunkRewrite rewrite;

        RewriteTask(DimensionType dimension, ChunkPos pos, RegionChunkWriter.ChunkRewrite rewrite) {
            this.dimension = dimension;
            this.pos = pos;
            this.rewrite = rewrite;
        }

        DimensionType dimension() {
            return dimension;
        }

        ChunkPos pos() {
            return pos;
        }

        RegionChunkWriter.ChunkRewrite rewrite() {
            return rewrite;
        }
    }

    private static final class ScanTask implements Task {
        private final Target target;
        private final DimensionType dimension;
        private final ChunkPos pos;

        ScanTask(Target target, DimensionType dimension, ChunkPos pos) {
            this.target = target;
            this.dimension = dimension;
            this.pos = pos;
        }

        Target target() {
            return target;
        }

        DimensionType dimension() {
            return dimension;
        }

        ChunkPos pos() {
            return pos;
        }
    }

    private static final class RunTask implements Task {
        private final Runnable action;

        RunTask(Runnable action) {
            this.action = action;
        }

        Runnable action() {
            return action;
        }
    }

    private static final class MapBatchTask implements Task {
        private final List<Runnable> writes;

        MapBatchTask(List<Runnable> writes) {
            this.writes = writes;
        }

        List<Runnable> writes() {
            return writes;
        }
    }

    private static final class FinalizeTask implements Task {
        static final FinalizeTask INSTANCE = new FinalizeTask();

        FinalizeTask() {}
    }

    private final StorageOpener regionOpener;
    private final Preflight preflight;
    private final Finalizer finalizer;
    private final OutputFinalizer outputs;
    private final SaveProgress progress;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<SaveResult> result = new CompletableFuture<>();
    private final Thread thread;

    // Total write tasks handed over so far, the denominator of the HUD's chunk-drain bar. Incremented on the
    // main thread at submit and read on the writer thread, hence volatile; only the main thread writes it.
    private volatile long submitted;

    // The recovered-coverage observer, or null on a fresh download. A passive outline side-channel run from the
    // read-only resume scan (scanResumeChunk), entirely off the chunk write path, so it can never affect a write.
    // Set on the main thread before the first submit, read on the writer thread.
    private volatile @Nullable BiConsumer<DimensionType, CompoundTag> resumeReadObserver;

    // The entity-part recovered observer, the entity sibling of resumeReadObserver: fed the prior on-disk region
    // chunk by an entity-targeted resume scan, and reads its Level.Entities part, off the chunk write path. Same
    // threading discipline.
    private volatile @Nullable BiConsumer<DimensionType, CompoundTag> resumeEntityReadObserver;

    /**
     * The three writer-thread steps run around the drain, in lifecycle order: {@code preflight} before any chunk is
     * written into the folder (the pre-merge resume backup), {@code finalizer} after the drain while the folder is open
     * (the band's level.dat write), and {@code outputs} after the storages have closed (the export zip).
     * {@code preflight} and {@code outputs} are best-effort: a throw from either is caught and logged, never failing
     * the save, so a zip failure can never endanger the openable folder. A {@code finalizer} throw aborts the save the
     * usual way (it is the level.dat write), except that 1.15.2 vanilla LevelStorage.saveLevelData catches and logs an
     * IO failure rather than throwing, so a disk-level level.dat write failure is not surfaced here.
     */
    public AsyncSaveWriter(StorageOpener regionOpener, Preflight preflight,
            Finalizer finalizer, OutputFinalizer outputs, SaveProgress progress) {
        this.regionOpener = regionOpener;
        this.preflight = preflight;
        this.finalizer = finalizer;
        this.outputs = outputs;
        this.progress = progress;
        this.thread = new Thread(this::run, "wdl-save-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Set the observer each {@link #submitResumeScan} hands its on-disk chunk and dimension, for the outline's
     * recovered-coverage scan. Call on the main thread before the first submit; the observer runs on the writer thread,
     * which owns the on-disk read, entirely off the chunk write path.
     */
    public void observeResumeReads(BiConsumer<DimensionType, CompoundTag> observer) {
        this.resumeReadObserver = observer;
    }

    /**
     * Set the observer each {@link #submitEntityResumeScan} hands its on-disk {@code region/} chunk and dimension, for
     * the outline's recovered-entity scan; the observer reads the chunk's {@code Level.Entities} part. The entity
     * sibling of {@link #observeResumeReads}; same threading rules.
     */
    public void observeEntityResumeReads(BiConsumer<DimensionType, CompoundTag> observer) {
        this.resumeEntityReadObserver = observer;
    }

    /**
     * Enqueue a read-only scan of {@code dimension}'s on-disk chunk at {@code pos} for recovered coverage: the writer
     * reads the prior chunk it owns and reports it to the observer without writing, so a resumed chunk's prior-captured
     * positions are known while it is still in view, not only when it later flushes. Call on the main thread; a no-op
     * when no observer is set. Self-gating on a fresh download (the caller scans only on a resume), and the read
     * returns empty for any chunk with no prior.
     */
    public void submitResumeScan(DimensionType dimension, ChunkPos pos) {
        queue.add(new ScanTask(Target.REGION, dimension, pos));
    }

    /**
     * Enqueue a read-only scan of {@code dimension}'s on-disk {@code region/} chunk at {@code pos} for recovered
     * coverage, the entity sibling of {@link #submitResumeScan}: the writer reads the prior chunk it owns and reports
     * it to the entity observer, which reads the {@code Level.Entities} part, without writing. Call on the main thread;
     * a no-op when no entity observer is set, and the read returns empty for any chunk with no prior.
     */
    public void submitEntityResumeScan(DimensionType dimension, ChunkPos pos) {
        queue.add(new ScanTask(Target.ENTITIES, dimension, pos));
    }

    /**
     * Enqueue an off-render-thread action to run on the writer thread, in order with the chunk drain (the coverage
     * overlay's resume prior-coverage seed and the map data writes streamed during capture). Neither touches the
     * writer's own region stores (raw region-header reads, single data/ file writes), so a task never contends with
     * them. A {@link RuntimeException} is caught and logged, never aborting the save; a task owning a soft-failure
     * tally must catch and count its own exceptions. Call on the main thread before {@link #finish}; it runs before the
     * finalize.
     *
     * <p>A task submitted here reports no progress, so the HUD bar sits at its last chunk fraction while the task runs.
     * Use {@link #submitMapBatch} for the finish-time map writes, whose count is large enough to need a bar.
     */
    public void submit(Runnable action) {
        queue.add(new RunTask(action));
    }

    /**
     * Enqueue the finish-time map data writes as one counted batch (main thread, before {@link #finish}). The writer
     * runs them in order, reporting one step per write through {@link SaveProgress#maps}, so the bar advances over a
     * known total rather than freezing mid-chunk-phase the way a pile of uncounted {@link #submit} tasks would. Submit
     * after the last chunk and entity write so the phases advance in turn. A no-op for an empty list, which leaves the
     * map phase unpublished and the bar going straight from chunks to compressing.
     */
    public void submitMapBatch(List<Runnable> writes) {
        if (writes.isEmpty()) {
            return;
        }
        queue.add(new MapBatchTask(ImmutableList.copyOf(writes)));
    }

    /**
     * Enqueue a lazy encode-and-fold thunk for {@code dimension}'s {@code region/} storage (main thread). The writer
     * thread resolves {@code encode} (the heavy chunk serialize plus the container/lectern fold), so it must close over
     * only immutable, detached inputs the writer then solely owns (the snapshot, the drained holders, the per-band
     * codec/sinks, the frozen registries). {@code merge} is the on-disk read-merge the writer applies to that chunk,
     * supplied per chunk because it closes over what only the main thread can read off the captured snapshot.
     */
    public void submitChunk(DimensionType dimension, ChunkPos pos, Supplier<CompoundTag> encode,
            RegionChunkWriter.ChunkReadMerge merge) {
        submitted++;
        queue.add(new WriteTask(Target.REGION, dimension, pos, encode, merge));
    }

    /**
     * Enqueue a read-modify-write of {@code dimension}'s already-written {@code region/} chunk at {@code pos} (main
     * thread): the writer reads the on-disk chunk, applies {@code rewrite} to fold detached open-time holders onto its
     * block entities, and writes it back. The recovery path for a container or lectern opened in a chunk that had
     * already flushed and left the keep-hot buffer; {@code rewrite} must close over only immutable, detached data the
     * writer then solely owns (the drained holders, the per-band sinks).
     */
    public void submitChunkRewrite(DimensionType dimension, ChunkPos pos, RegionChunkWriter.ChunkRewrite rewrite) {
        submitted++;
        queue.add(new RewriteTask(dimension, pos, rewrite));
    }

    /**
     * Enqueue an encoded entity carrier to fold into {@code dimension}'s {@code region/} chunk at {@code pos} under
     * {@code Level.Entities} (main thread). The writer thread owns {@code tag} after this call; the caller must not
     * mutate it afterward.
     */
    public void submitEntity(DimensionType dimension, ChunkPos pos, CompoundTag tag) {
        submitted++;
        // The merge argument is inert for an entity task: the drain routes it through foldEntitiesIntoRegion, which
        // applies EntityMerge.merge against the host chunk's Level.Entities itself. WriteTask still requires one.
        queue.add(new WriteTask(Target.ENTITIES, dimension, pos, () -> tag, EntityMerge::merge));
    }

    /**
     * Signal end-of-stream; the writer drains, finalizes, closes, and completes the returned future. Call after the
     * last submit: any {@link #submitChunk}/{@link #submitEntity} after this marker is silently dropped (the writer
     * loop has already exited).
     */
    public CompletableFuture<SaveResult> finish() {
        queue.add(FinalizeTask.INSTANCE);
        return result;
    }

    /** The completion future, polled on the main thread (never completed exceptionally). */
    public CompletableFuture<SaveResult> result() {
        return result;
    }

    private void run() {
        // One storage per dimension, opened on demand: a session that follows the player across a portal writes
        // each dimension's chunks/entities to its own vanilla folder (overworld region/, nether DIM-1/, ...).
        Storages regions = new Storages(regionOpener, "region");
        int chunksNew = 0;
        int chunksRecaptured = 0;
        int mergedContainers = 0;
        int chunksFailed = 0;
        int entityChunksWritten = 0;
        int entityChunksFailed = 0;
        int entitiesCarriedForward = 0;
        @Nullable
        Throwable error = null;
        try {
            // The pre-merge safety copy runs before the drain opens any storage, so a resume is backed up before
            // it modifies the folder. Best-effort: a failed backup never aborts the save.
            bestEffort(preflight, "the resume backup");
            long drained = 0;
            Task next;
            while (!((next = queue.take()) instanceof FinalizeTask)) {
                if (next instanceof ScanTask) {
                    ScanTask scan = (ScanTask) next;
                    scanResumeChunk(scan, regions);
                    continue;
                }
                if (next instanceof RunTask) {
                    RunTask run = (RunTask) next;
                    try {
                        run.action().run();
                    } catch (RuntimeException e) {
                        LOGGER.warn("an off-thread task failed; the save is unaffected", e);
                    }
                    continue;
                }
                if (next instanceof MapBatchTask) {
                    MapBatchTask maps = (MapBatchTask) next;
                    writeMapBatch(maps.writes());
                    continue;
                }
                if (next instanceof RewriteTask) {
                    RewriteTask rewrite = (RewriteTask) next;
                    progress.chunks(++drained, submitted);
                    WdlRegionStorage region = regions.storageFor(rewrite.dimension());
                    // An unopenable dimension leaves no on-disk chunk to fold the orphaned contents onto, the
                    // same outcome rewriteExisting reports for a position with no prior, and neither has a
                    // tally of its own.
                    if (region != null) {
                        mergedContainers += RegionChunkWriter.rewriteExisting(region, rewrite.pos(), rewrite.rewrite());
                    }
                    continue;
                }
                WriteTask task = (WriteTask) next;
                progress.chunks(++drained, submitted); // both REGION and ENTITIES drain under the one chunks phase
                CompoundTag tag;
                try {
                    tag = task.encode().get(); // encode + stage-(a) fold on this thread (a no-op for entities)
                } catch (RuntimeException e) {
                    // Per-chunk isolation, the existing discipline (captureLoadedChunks / RegionChunkWriter): one
                    // chunk's deferred encode throwing must not abort the stream. Count it failed and move on.
                    LOGGER.warn("skipping chunk {}: encode failed", task.pos(), e);
                    if (task.target() == Target.REGION) {
                        chunksFailed++;
                    } else {
                        entityChunksFailed++;
                    }
                    continue;
                }
                if (task.target() == Target.REGION) {
                    WdlRegionStorage region = regions.storageFor(task.dimension());
                    if (region == null) {
                        // The cause is per dimension and is logged there once; the loss is per chunk, because
                        // each task whose storage never opened is a chunk that did not reach disk, exactly what
                        // a failed write is. Counting the dimension once instead would report one loss for a
                        // whole dimension of missing terrain.
                        chunksFailed++;
                        continue;
                    }
                    // A fresh download reads empty for most chunks, but not for all of them: a revisit re-writes
                    // a position this session already wrote, which is why the merge is supplied per chunk rather
                    // than fixed here.
                    RegionChunkWriter.MergeWriteResult merged = RegionChunkWriter.writeMerging(region, task.pos(), tag,
                            task.merge());
                    switch (merged.outcome()) {
                        case WRITTEN_NEW:
                            chunksNew++;
                            break;
                        case WRITTEN_RECAPTURED:
                            chunksRecaptured++;
                            mergedContainers += merged.mergeBacks();
                            break;
                        // A preserve wrote nothing, so this session's capture of the chunk went nowhere, which
                        // is what the failed term counts. Nothing to write is the other thing entirely, a
                        // chunk with no content of its own.
                        case FAILED:
                        case PRESERVED:
                            chunksFailed++;
                            break;
                        case NOTHING_TO_WRITE:
                            break;
                    }
                } else {
                    WdlRegionStorage region = regions.storageFor(task.dimension());
                    if (region == null) {
                        entityChunksFailed++; // per chunk, as above
                        continue;
                    }
                    // At 1.15.2 entities live inside the region/ chunk under Level.Entities, so the entity write is a
                    // fold into the host chunk, not a separate entities/ store. The fold read-merges too: a
                    // re-captured host carries forward each on-disk vehicle's contents AND every on-disk entity the
                    // fresh capture lacks (EntityMerge unions, so a partial re-flush adds to rather than overwrites
                    // the prior set). Counted separately from block containers: a non-zero tally means chunks were
                    // re-flushed with partial sets. A host chunk that never reached disk is a lost fold (FAILED),
                    // since an entity cannot live without its terrain at this band.
                    RegionChunkWriter.MergeWriteResult merged = RegionChunkWriter.foldEntitiesIntoRegion(region,
                            task.pos(), tag);
                    switch (merged.outcome()) {
                        case WRITTEN_NEW:
                        case WRITTEN_RECAPTURED:
                            entityChunksWritten++;
                            entitiesCarriedForward += merged.mergeBacks();
                            break;
                        case FAILED:
                        case PRESERVED:
                            entityChunksFailed++; // as above
                            break;
                        case NOTHING_TO_WRITE:
                            break;
                    }
                }
            }
            finalizer.run(chunksFailed, entityChunksFailed); // level.dat, idcounts, and the completion record
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = e;
        } catch (Throwable e) {
            error = e;
        } finally {
            regions.closeAll();
        }
        // The finalize-time output (the export zip) runs only after the folder is fully written and closed, and
        // only on a clean save: a failed save must not be zipped. Best-effort, so a zip failure never turns a
        // successful save into a reported failure.
        String zipFileName = null;
        if (error == null) {
            zipFileName = bestEffortOutput(outputs, "the export zip");
        }
        result.complete(new SaveResult(chunksNew, chunksRecaptured, mergedContainers, chunksFailed,
                entityChunksWritten, entityChunksFailed, entitiesCarriedForward, zipFileName, error));
    }

    /**
     * Writer thread: run the batched map data writes in order, publishing the map phase across them. Each write is
     * isolated the way the {@link RunTask} branch isolates a single task, so one map's throw cannot escape the drain
     * loop and abort the save before the level.dat finalize. A write owning a soft-failure tally counts its own
     * failures, which is why this catch only logs.
     */
    private void writeMapBatch(List<Runnable> writes) {
        int total = writes.size();
        progress.maps(0, total);
        int written = 0;
        for (Runnable write : writes) {
            try {
                write.run();
            } catch (RuntimeException e) {
                LOGGER.warn("a batched map data write failed; the save is unaffected", e);
            }
            progress.maps(++written, total);
        }
    }

    /**
     * Writer thread: read {@code scan}'s on-disk {@code region/} chunk this writer owns and hand it to the target's
     * recovered-coverage observer without writing. Both targets scan the same {@code region/} chunk at this band (block
     * entities and entities are siblings inside it); the observer reads its own part (terrain or
     * {@code Level.Entities}). Isolated: a failure is logged and never touches a chunk write.
     */
    private void scanResumeChunk(ScanTask scan, Storages regions) {
        boolean region = scan.target() == Target.REGION;
        BiConsumer<DimensionType, CompoundTag> observer = region ? resumeReadObserver : resumeEntityReadObserver;
        if (observer == null) {
            return;
        }
        WdlRegionStorage storage = regions.storageFor(scan.dimension());
        if (storage == null) {
            return; // the dimension logged its own open failure; a scan reads nothing and loses nothing
        }
        try {
            CompoundTag onDisk = storage.read(scan.pos());
            if (onDisk != null) {
                observer.accept(scan.dimension(), onDisk);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("chunk {} recovered-coverage scan failed", scan.pos(), e);
        }
    }

    /** Run a best-effort writer-thread step (the resume backup); a throw is logged, never fatal. */
    private static void bestEffort(Preflight step, String what) {
        try {
            step.run();
        } catch (Throwable e) {
            LOGGER.warn("a finalize step failed ({}); the openable save is unaffected", what, e);
        }
    }

    /** As {@link #bestEffort}, for the output step: a throw is logged and reports no written zip. */
    private static @Nullable String bestEffortOutput(OutputFinalizer step, String what) {
        try {
            return step.run();
        } catch (Throwable e) {
            LOGGER.warn("a finalize step failed ({}); the openable save is unaffected", what, e);
            return null;
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close a save resource", e);
        }
    }

    /**
     * The writer thread's storages for one target ({@code region/} or {@code entities/}), one per dimension, opened on
     * that dimension's first task. Both instances are locals of {@link #run}, reachable only from it and from what it
     * calls on its own thread, so they need no synchronization.
     *
     * <p>A failed open is retried on the dimension's next task rather than remembered, because the failure can be of
     * the moment: this project's own Windows gate has shown transient file locks to be real, and a dimension written
     * off on one chunk would cost every later chunk it holds for the rest of the download. What is remembered is the
     * LOG, one stack per dimension, since one root cause fails every chunk of that dimension alike and a stack per
     * chunk buries it under as many copies as the dimension is wide. The caller counts what each dropped task costs,
     * which is why the count is per chunk while the cause is per dimension.
     */
    private static final class Storages {
        private final StorageOpener opener;
        private final String target;
        private final Map<DimensionType, WdlRegionStorage> open = new LinkedHashMap<>();
        private final Set<DimensionType> loggedFailures = new HashSet<>();

        private Storages(StorageOpener opener, String target) {
            this.opener = opener;
            this.target = target;
        }

        /**
         * The storage for {@code dimension}, opened on demand, or null when it cannot be opened. Null is the whole
         * reason this returns rather than throws: an opener that threw out of the drain loop would skip the finalize
         * and leave the chunks already on disk in a folder with no level.dat, which is a save the player cannot open,
         * so the caller counts the dropped task and the drain runs on to the finalize.
         */
        private @Nullable WdlRegionStorage storageFor(DimensionType dimension) {
            WdlRegionStorage storage = open.get(dimension);
            if (storage != null) {
                return storage;
            }
            try {
                storage = opener.open(dimension);
            } catch (Exception e) {
                if (loggedFailures.add(dimension)) {
                    LOGGER.warn("cannot open the {} storage for {}; every chunk of that dimension is lost", target,
                            DimensionType.getName(dimension), e);
                }
                return null;
            }
            open.put(dimension, storage);
            return storage;
        }

        private void closeAll() {
            open.values().forEach(AsyncSaveWriter::closeQuietly);
        }
    }
}
