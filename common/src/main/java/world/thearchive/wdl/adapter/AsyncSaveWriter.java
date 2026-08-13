// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.core.SaveProgress;

/**
 * A single background thread that owns the captured world's region storages and drains submitted chunks to disk, so the
 * client render thread never blocks on region I/O and never runs the heavy chunk serialize either. {@link #submitChunk}
 * hands over a lazy encode-and-fold {@link Supplier} that the writer thread resolves (the deferred but deterministic
 * encode of immutable, detached inputs); {@link #submitEntity} hands over a fully-encoded, immutable
 * {@link CompoundTag}. Either way the writer thread is the sole owner of the {@link SimpleRegionStorage}s, the
 * one-writer invariant, and the reason only immutable or deferred-immutable work may cross the queue.
 *
 * <p>That invariant is this class's own, not one vanilla imposes: the region {@code IOWorker} is safe to call from any
 * thread, because it queues onto a concurrent queue and serializes execution by a status compare-and-set rather than by
 * caller identity. What needs the single thread is here. The two per-dimension {@link Storages} are unsynchronized and
 * open lazily by get-then-put, so a second writer could open two storages over one directory. The merge and rewrite
 * paths are read-modify-write per {@link ChunkPos} and would lose a merge if interleaved. And the finalize order is
 * fixed.
 *
 * <p>{@link #finish()} enqueues an end-of-stream marker; the writer drains the remaining tags,
 * {@code synchronize(true)}s and closes each storage, runs the {@link Finalizer} (the band's level.dat write), closes
 * the supplied {@link AutoCloseable} (the {@code LevelStorageAccess} holding the session lock), and completes the
 * returned future with the per-target tallies (or the error that aborted it). The future is completed normally even on
 * failure, so the caller polls it with one branch.
 */
final class AsyncSaveWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Lazily opens a region storage on the writer thread; called once per (target, dimension) on its first tag. The
     * dimension lets one writer follow the player across a portal, opening each dimension's storage on demand, so two
     * same-position chunks in different dimensions never collide on one storage.
     */
    @FunctionalInterface
    public interface StorageOpener {
        SimpleRegionStorage open(ResourceKey<Level> dimension) throws Exception;
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
     * The best-effort finish-time output step run after the storages and session lock have closed (the export zip).
     * Returns the written zip's filename for the completion surfaces, or null when none was written.
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
    record SaveResult(int chunksNew, int chunksRecaptured, int mergedContainers, int chunksFailed,
            int entityChunksWritten, int entityChunksFailed, int entitiesCarriedForward,
            @Nullable String zipFileName, @Nullable Throwable error) {
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

    private sealed interface Task permits WriteTask, RewriteTask, ScanTask, RunTask, MapBatchTask, FinalizeTask {}

    private record WriteTask(Target target, ResourceKey<Level> dimension, ChunkPos pos,
            Supplier<CompoundTag> encode, RegionChunkWriter.ChunkReadMerge merge) implements Task {}

    private record RewriteTask(ResourceKey<Level> dimension, ChunkPos pos,
            RegionChunkWriter.ChunkRewrite rewrite) implements Task {}

    private record ScanTask(Target target, ResourceKey<Level> dimension, ChunkPos pos) implements Task {}

    private record RunTask(Runnable action) implements Task {}

    private record MapBatchTask(List<Runnable> writes) implements Task {}

    private record FinalizeTask() implements Task {
        static final FinalizeTask INSTANCE = new FinalizeTask();
    }

    private final StorageOpener regionOpener;
    private final StorageOpener entitiesOpener;
    private final Preflight preflight;
    private final Finalizer finalizer;
    private final OutputFinalizer outputs;
    private final AutoCloseable access;
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
    private volatile @Nullable BiConsumer<ResourceKey<Level>, CompoundTag> resumeReadObserver;

    // The entities-store recovered observer, the entity sibling of resumeReadObserver: fed the prior on-disk
    // entity chunk by an entities-targeted resume scan, off the chunk write path. Same threading discipline.
    private volatile @Nullable BiConsumer<ResourceKey<Level>, CompoundTag> resumeEntityReadObserver;

    /**
     * The three writer-thread steps run around the drain, in lifecycle order: {@code preflight} before any chunk is
     * written into the folder (the pre-merge resume backup), {@code finalizer} after the drain while the folder is open
     * (the band's level.dat write), and {@code outputs} after the storages and session lock have closed (the export
     * zip). {@code preflight} and {@code outputs} are best-effort: a throw from either is caught and logged, never
     * failing the save, so a zip failure can never endanger the openable folder. A {@code finalizer} throw aborts the
     * save the usual way (it is the level.dat write).
     */
    public AsyncSaveWriter(StorageOpener regionOpener, StorageOpener entitiesOpener, Preflight preflight,
            Finalizer finalizer, OutputFinalizer outputs, AutoCloseable access, SaveProgress progress) {
        this.regionOpener = regionOpener;
        this.entitiesOpener = entitiesOpener;
        this.preflight = preflight;
        this.finalizer = finalizer;
        this.outputs = outputs;
        this.access = access;
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
    public void observeResumeReads(BiConsumer<ResourceKey<Level>, CompoundTag> observer) {
        this.resumeReadObserver = observer;
    }

    /**
     * Set the observer each {@link #submitEntityResumeScan} hands its on-disk entity chunk and dimension, for the
     * outline's recovered-entity scan. The entity sibling of {@link #observeResumeReads}; same threading rules.
     */
    public void observeEntityResumeReads(BiConsumer<ResourceKey<Level>, CompoundTag> observer) {
        this.resumeEntityReadObserver = observer;
    }

    /**
     * Enqueue a read-only scan of {@code dimension}'s on-disk chunk at {@code pos} for recovered coverage: the writer
     * reads the prior chunk it owns and reports it to the observer without writing, so a resumed chunk's prior-captured
     * positions are known while it is still in view, not only when it later flushes. Call on the main thread; a no-op
     * when no observer is set. Self-gating on a fresh download (the caller scans only on a resume), and the read
     * returns empty for any chunk with no prior.
     */
    public void submitResumeScan(ResourceKey<Level> dimension, ChunkPos pos) {
        queue.add(new ScanTask(Target.REGION, dimension, pos));
    }

    /**
     * Enqueue a read-only scan of {@code dimension}'s on-disk entity chunk at {@code pos} for recovered coverage, the
     * entity sibling of {@link #submitResumeScan}: the writer reads the prior entities chunk it owns and reports it to
     * the entity observer without writing. Call on the main thread; a no-op when no entity observer is set, and the
     * read returns empty for any chunk with no prior.
     */
    public void submitEntityResumeScan(ResourceKey<Level> dimension, ChunkPos pos) {
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
        queue.add(new MapBatchTask(List.copyOf(writes)));
    }

    /**
     * Enqueue a lazy encode-and-fold thunk for {@code dimension}'s {@code region/} storage (main thread). The writer
     * thread resolves {@code encode} (the heavy chunk serialize plus the container/lectern fold), so it must close over
     * only immutable, detached inputs the writer then solely owns (the snapshot, the drained holders, the per-band
     * codec/sinks, the frozen registries). {@code merge} is the on-disk read-merge the writer applies to that chunk,
     * supplied per chunk because it closes over what only the main thread can read off the captured snapshot.
     */
    public void submitChunk(ResourceKey<Level> dimension, ChunkPos pos, Supplier<CompoundTag> encode,
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
    public void submitChunkRewrite(ResourceKey<Level> dimension, ChunkPos pos, RegionChunkWriter.ChunkRewrite rewrite) {
        submitted++;
        queue.add(new RewriteTask(dimension, pos, rewrite));
    }

    /**
     * Enqueue an encoded entity-chunk tag for {@code dimension}'s {@code entities/} storage (main thread). The writer
     * thread owns {@code tag} after this call; the caller must not mutate it afterward.
     */
    public void submitEntity(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag tag) {
        submitted++;
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
        Storages entities = new Storages(entitiesOpener, "entities");
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
                if (next instanceof ScanTask scan) {
                    scanResumeChunk(scan, regions, entities);
                    continue;
                }
                if (next instanceof RunTask run) {
                    try {
                        run.action().run();
                    } catch (RuntimeException e) {
                        LOGGER.warn("an off-thread task failed; the save is unaffected", e);
                    }
                    continue;
                }
                if (next instanceof MapBatchTask maps) {
                    writeMapBatch(maps.writes());
                    continue;
                }
                if (next instanceof RewriteTask rewrite) {
                    progress.chunks(++drained, submitted);
                    SimpleRegionStorage region = regions.storageFor(rewrite.dimension());
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
                    SimpleRegionStorage region = regions.storageFor(task.dimension());
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
                        case WRITTEN_NEW -> chunksNew++;
                        case WRITTEN_RECAPTURED -> {
                            chunksRecaptured++;
                            mergedContainers += merged.mergeBacks();
                        }
                        // A preserve wrote nothing, so this session's capture of the chunk went nowhere, which
                        // is what the failed term counts. Nothing to write is the other thing entirely, a
                        // chunk with no content of its own.
                        case FAILED, PRESERVED -> chunksFailed++;
                        case NOTHING_TO_WRITE -> {}
                    }
                } else {
                    SimpleRegionStorage entityStore = entities.storageFor(task.dimension());
                    if (entityStore == null) {
                        entityChunksFailed++; // per chunk, as above
                        continue;
                    }
                    // The entities target read-merges too: a re-captured entity-chunk carries forward each on-disk
                    // vehicle's contents AND every on-disk entity the fresh capture lacks (EntityMerge unions, so
                    // a partial re-flush adds to rather than overwrites the prior set). Counted separately from
                    // block containers: a non-zero tally means chunks were re-flushed with partial sets.
                    RegionChunkWriter.MergeWriteResult merged = RegionChunkWriter.writeMerging(entityStore,
                            task.pos(), tag, task.merge());
                    switch (merged.outcome()) {
                        case WRITTEN_NEW, WRITTEN_RECAPTURED -> {
                            entityChunksWritten++;
                            entitiesCarriedForward += merged.mergeBacks();
                        }
                        case FAILED, PRESERVED -> entityChunksFailed++; // as above
                        case NOTHING_TO_WRITE -> {}
                    }
                }
            }
            regions.synchronizeAll();
            entities.synchronizeAll();
            finalizer.run(chunksFailed, entityChunksFailed); // level.dat, idcounts, and the completion record
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = e;
        } catch (Throwable e) {
            error = e;
        } finally {
            regions.closeAll();
            entities.closeAll();
            closeQuietly(access);
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
     * Writer thread: read {@code scan}'s on-disk chunk from the target storage this writer owns (region/ or entities/)
     * and hand it to that target's recovered-coverage observer without writing. Isolated: a failure is logged and never
     * touches a chunk write.
     */
    private void scanResumeChunk(ScanTask scan, Storages regions, Storages entities) {
        boolean region = scan.target() == Target.REGION;
        BiConsumer<ResourceKey<Level>, CompoundTag> observer = region ? resumeReadObserver : resumeEntityReadObserver;
        if (observer == null) {
            return;
        }
        SimpleRegionStorage storage = (region ? regions : entities).storageFor(scan.dimension());
        if (storage == null) {
            return; // the dimension logged its own open failure; a scan reads nothing and loses nothing
        }
        try {
            storage.read(scan.pos()).join().ifPresent(onDisk -> observer.accept(scan.dimension(), onDisk));
        } catch (RuntimeException e) {
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
        private final Map<ResourceKey<Level>, SimpleRegionStorage> open = new LinkedHashMap<>();
        private final Set<ResourceKey<Level>> loggedFailures = new HashSet<>();

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
        private @Nullable SimpleRegionStorage storageFor(ResourceKey<Level> dimension) {
            SimpleRegionStorage storage = open.get(dimension);
            if (storage != null) {
                return storage;
            }
            try {
                storage = opener.open(dimension);
            } catch (Exception e) {
                if (loggedFailures.add(dimension)) {
                    LOGGER.warn("cannot open the {} storage for {}; every chunk of that dimension is lost", target,
                            dimension.location(), e);
                }
                return null;
            }
            open.put(dimension, storage);
            return storage;
        }

        /**
         * Flush every open storage, logging rather than throwing a failure. Vanilla's flush is a channel force, and
         * every chunk write was already awaited before it, so what a failure puts in doubt is whether those bytes
         * survive a power loss, not whether the file system has them. Nothing is counted for it: the close that follows
         * forces every region file again, so the failure this catch sees is usually resolved by the next statement, and
         * this project's recorded posture is that region data is forced once at finalize and promises nothing against
         * power loss. What the catch is for is the step behind it, the level.dat write, which a throw here would skip
         * and leave a folder the player cannot open.
         */
        private void synchronizeAll() {
            for (Map.Entry<ResourceKey<Level>, SimpleRegionStorage> entry : open.entrySet()) {
                try {
                    entry.getValue().synchronize(true).join();
                } catch (RuntimeException e) {
                    LOGGER.warn("cannot flush the {} storage for {}; its chunks may not survive a power loss",
                            target, entry.getKey().location(), e);
                }
            }
        }

        private void closeAll() {
            open.values().forEach(AsyncSaveWriter::closeQuietly);
        }
    }
}
