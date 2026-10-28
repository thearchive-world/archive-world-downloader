// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.logging.LogUtils;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import world.thearchive.wdl.core.SaveProgress;

/**
 * The two per-dimension {@link Storages} are unsynchronized and open lazily by get-then-put, so a second writer could
 * open two storages over one directory. The merge and rewrite paths are read-modify-write per {@link ChunkPos} and
 * would lose a merge if interleaved.
 */
final class AsyncSaveWriter {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Overrunning it can cost the very chunk the pause exists to protect.
    private static final long ENCODE_HANDSHAKE_TIMEOUT_MILLIS = 250L;

    private static final long FINALIZE_HANDSHAKE_TIMEOUT_MILLIS = 2_000L;

    private static final long PAUSE_SELF_RELEASE_TIMEOUT_MILLIS = 10_000L;

    @FunctionalInterface
    public interface StorageOpener {
        IOWorker open(ResourceKey<Level> dimension) throws Exception;
    }

    @FunctionalInterface
    public interface Preflight {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface LevelDataWrite {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface Finalizer {
        void run(int chunksFailed, int entityChunksFailed) throws Exception;
    }

    @FunctionalInterface
    public interface OutputFinalizer {
        @Nullable
        String run() throws Exception;
    }

    record SaveResult(int chunksNew, int chunksRecaptured, int mergedContainers, int chunksFailed,
            int entityChunksWritten, int entityChunksFailed, int entitiesCarriedForward, int entitiesRecovered,
            @Nullable String zipFileName, @Nullable Throwable error) {
        public boolean failed() {
            return error != null;
        }

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
    private final LevelDataWrite levelDataWrite;
    private final Finalizer finalizer;
    private final OutputFinalizer outputs;
    private final AutoCloseable access;
    private final SaveProgress progress;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<SaveResult> result = new CompletableFuture<>();
    private final Object encodeLock = new Object();
    private boolean encodingPaused;
    private boolean permitHeld;
    private boolean finalizeHoldsPermit;
    private int guardLapses;
    private final Thread thread;

    private volatile long submitted;

    private volatile @Nullable BiConsumer<ResourceKey<Level>, CompoundTag> resumeReadObserver;

    private volatile @Nullable BiConsumer<ResourceKey<Level>, CompoundTag> resumeEntityReadObserver;

    private volatile @Nullable RecoveredEntityContent recoveredEntityContent;

    public AsyncSaveWriter(StorageOpener regionOpener, StorageOpener entitiesOpener, Preflight preflight,
            LevelDataWrite levelDataWrite, Finalizer finalizer, OutputFinalizer outputs, AutoCloseable access,
            SaveProgress progress) {
        this.regionOpener = regionOpener;
        this.entitiesOpener = entitiesOpener;
        this.preflight = preflight;
        this.levelDataWrite = levelDataWrite;
        this.finalizer = finalizer;
        this.outputs = outputs;
        this.access = access;
        this.progress = progress;
        this.thread = new Thread(this::run, "wdl-save-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void observeResumeReads(BiConsumer<ResourceKey<Level>, CompoundTag> observer) {
        this.resumeReadObserver = observer;
    }

    public void observeEntityResumeReads(BiConsumer<ResourceKey<Level>, CompoundTag> observer) {
        this.resumeEntityReadObserver = observer;
    }

    public void carryRecoveredEntityContent(RecoveredEntityContent content) {
        this.recoveredEntityContent = content;
    }

    public void submitResumeScan(ResourceKey<Level> dimension, ChunkPos pos) {
        queue.add(new ScanTask(Target.REGION, dimension, pos));
    }

    public void submitEntityResumeScan(ResourceKey<Level> dimension, ChunkPos pos) {
        queue.add(new ScanTask(Target.ENTITIES, dimension, pos));
    }

    /**
     * A {@link RuntimeException} is caught and logged, never aborting the save; a task owning a soft-failure tally must
     * catch and count its own exceptions.
     */
    public void submit(Runnable action) {
        queue.add(new RunTask(action));
    }

    /**
     * The writer runs them in order, reporting one step per write through {@link SaveProgress#maps}, so the bar
     * advances over a known total rather than freezing mid-chunk-phase the way a pile of uncounted {@link #submit}
     * tasks would. Submit after the last chunk and entity write so the phases advance in turn. A no-op for an empty
     * list, which leaves the map phase unpublished.
     */
    public void submitMapBatch(List<Runnable> writes) {
        if (writes.isEmpty()) {
            return;
        }
        queue.add(new MapBatchTask(List.copyOf(writes)));
    }

    public void submitChunk(ResourceKey<Level> dimension, ChunkPos pos, Supplier<CompoundTag> encode,
            RegionChunkWriter.ChunkReadMerge merge) {
        submitted++;
        queue.add(new WriteTask(Target.REGION, dimension, pos, encode, merge));
    }

    public void submitChunkRewrite(ResourceKey<Level> dimension, ChunkPos pos, RegionChunkWriter.ChunkRewrite rewrite) {
        submitted++;
        queue.add(new RewriteTask(dimension, pos, rewrite));
    }

    /**
     * The writer thread owns {@code tag} after this call; the caller must not mutate it afterward.
     */
    public void submitEntity(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag tag) {
        submitted++;
        queue.add(new WriteTask(Target.ENTITIES, dimension, pos, () -> tag, EntityMerge::merge));
    }

    /**
     * Call after the last submit: any {@link #submitChunk}/{@link #submitEntity} after this marker is silently dropped.
     */
    public CompletableFuture<SaveResult> finish() {
        queue.add(FinalizeTask.INSTANCE);
        return result;
    }

    public CompletableFuture<SaveResult> result() {
        return result;
    }

    /**
     * The loader can rebuild the client's registries in place when a level closes: a chunk encode reading them
     * meanwhile resolves blocks to nothing.
     *
     * <p>The wait is bounded because it can run on the client thread: a read that overruns it is let go rather than
     * freezing the client, risking the one chunk this was protecting, or the level.dat where a loader's save hook reads
     * the registries.
     */
    public void pauseEncoding() {
        long startNanos = System.nanoTime();
        try {
            synchronized (encodeLock) {
                encodingPaused = true;
                long timeoutMillis = finalizeHoldsPermit ? FINALIZE_HANDSHAKE_TIMEOUT_MILLIS
                        : ENCODE_HANDSHAKE_TIMEOUT_MILLIS;
                long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
                while (permitHeld) {
                    long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainingMillis <= 0) {
                        // Counted, not just logged: the save this may have torn has to reach the player as partial.
                        guardLapses++;
                        if (finalizeHoldsPermit) {
                            LOGGER.warn("the level.dat write was still running after {} ms; letting the loader proceed "
                                    + "anyway, so what it wrote from the registries may be torn", timeoutMillis);
                        } else {
                            LOGGER.warn("an encode was still running after {} ms; letting the loader proceed anyway, "
                                    + "so this chunk may lose blocks", timeoutMillis);
                        }
                        return;
                    }
                    try {
                        encodeLock.wait(remainingMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            // INFO rather than DEBUG, and not behind a toggle, because a report of a stalled teardown arrives with the
            // game log and nothing else. Bounded without a toggle: nothing reaches this call unless a download is
            // running.
            LOGGER.info("held the save writer off the registry: the calling thread blocked for {} ms",
                    (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    boolean isEncodingPaused() {
        synchronized (encodeLock) {
            return encodingPaused;
        }
    }

    public void resumeEncoding() {
        synchronized (encodeLock) {
            encodingPaused = false;
            encodeLock.notifyAll();
        }
    }

    /**
     * Bounded because a resume that never arrives must not strand the save: the download would sit in saving forever.
     */
    private void acquireEncodePermit(boolean finalize) {
        synchronized (encodeLock) {
            long deadline = System.nanoTime() + PAUSE_SELF_RELEASE_TIMEOUT_MILLIS * 1_000_000L;
            while (encodingPaused) {
                long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    // Counted for the same reason as the handshake lapse: what follows runs unguarded.
                    guardLapses++;
                    LOGGER.warn("no resume arrived within {} ms; proceeding unguarded so the save can finalize",
                            PAUSE_SELF_RELEASE_TIMEOUT_MILLIS);
                    encodingPaused = false;
                    break;
                }
                try {
                    encodeLock.wait(remainingMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            permitHeld = true;
            finalizeHoldsPermit = finalize;
        }
    }

    private int guardLapses() {
        synchronized (encodeLock) {
            return guardLapses;
        }
    }

    private void releaseEncodePermit() {
        synchronized (encodeLock) {
            permitHeld = false;
            finalizeHoldsPermit = false;
            encodeLock.notifyAll();
        }
    }

    private void run() {
        Storages regions = new Storages(regionOpener, "region");
        Storages entities = new Storages(entitiesOpener, "entities");
        int chunksNew = 0;
        int chunksRecaptured = 0;
        int mergedContainers = 0;
        int chunksFailed = 0;
        int entityChunksWritten = 0;
        int entityChunksFailed = 0;
        int entitiesCarriedForward = 0;
        int entitiesRecovered = 0;
        @Nullable
        Throwable error = null;
        try {
            // The pre-merge safety copy runs before the drain opens any storage, so a resume is backed up before it
            // modifies the folder.
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
                    IOWorker region = regions.storageFor(rewrite.dimension());
                    if (region != null) {
                        mergedContainers += RegionChunkWriter.rewriteExisting(region, rewrite.pos(), rewrite.rewrite());
                    }
                    continue;
                }
                WriteTask task = (WriteTask) next;
                progress.chunks(++drained, submitted);
                CompoundTag tag;
                acquireEncodePermit(false);
                try {
                    tag = task.encode().get();
                } catch (RuntimeException e) {
                    // One chunk's deferred encode throwing must not abort the stream.
                    LOGGER.warn("skipping chunk {}: encode failed", task.pos(), e);
                    if (task.target() == Target.REGION) {
                        chunksFailed++;
                    } else {
                        entityChunksFailed++;
                    }
                    continue;
                } finally {
                    releaseEncodePermit();
                }
                if (task.target() == Target.REGION) {
                    IOWorker region = regions.storageFor(task.dimension());
                    if (region == null) {
                        // The cause is per dimension and is logged there once; the loss is per chunk, because
                        // each task whose storage never opened is a chunk that did not reach disk, exactly what
                        // a failed write is. Counting the dimension once instead would report one loss for a
                        // whole dimension of missing terrain.
                        chunksFailed++;
                        continue;
                    }
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
                    IOWorker entityStore = entities.storageFor(task.dimension());
                    if (entityStore == null) {
                        entityChunksFailed++; // per chunk, as above
                        continue;
                    }
                    // The after-merge step is what reaches an entity that changed entity-chunk between downloads: its
                    // prior record is in another chunk file, so this chunk's own read-merge has nothing to carry, and
                    // only content banked from the resume scan can fill it.
                    RecoveredEntityContent recovered = this.recoveredEntityContent;
                    RegionChunkWriter.MergeWriteResult merged = RegionChunkWriter.writeMerging(entityStore,
                            task.pos(), tag, task.merge(), recovered == null ? null : recovered::applyTo);
                    switch (merged.outcome()) {
                        case WRITTEN_NEW, WRITTEN_RECAPTURED -> {
                            entityChunksWritten++;
                            entitiesCarriedForward += merged.mergeBacks();
                            entitiesRecovered += merged.recoveredCarries();
                        }
                        case FAILED, PRESERVED -> entityChunksFailed++; // as above
                        case NOTHING_TO_WRITE -> {}
                    }
                }
            }
            regions.synchronizeAll();
            entities.synchronizeAll();
            // Under the permit although nothing here names a registry: a loader's level.dat hook can read the live
            // registries from this thread, and a rebuild overlapping it can tear the snapshot without a throw.
            acquireEncodePermit(true);
            long levelDataStartNanos = System.nanoTime();
            try {
                levelDataWrite.run();
            } finally {
                releaseEncodePermit();
            }
            long finalizeStartNanos = System.nanoTime();
            // No permit is held here, so the tally reaches the finalizer with every lapse the writer can count.
            finalizer.run(chunksFailed + guardLapses(), entityChunksFailed);
            LOGGER.info("ran the finalize: the level.dat write took {} ms and the remaining steps {} ms",
                    (finalizeStartNanos - levelDataStartNanos) / 1_000_000L,
                    (System.nanoTime() - finalizeStartNanos) / 1_000_000L);
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
        // A lapsed guard means a chunk or level.dat was written across a possible rebuild; it reaches this tally so
        // every surface this result feeds reports partial.
        result.complete(new SaveResult(chunksNew, chunksRecaptured, mergedContainers, chunksFailed + guardLapses(),
                entityChunksWritten, entityChunksFailed, entitiesCarriedForward, entitiesRecovered, zipFileName,
                error));
    }

    /**
     * Each write is isolated the way the {@link RunTask} branch isolates a single task, so one map's throw cannot
     * escape the drain loop and abort the save before the level.dat finalize. A write owning a soft-failure tally
     * counts its own failures, which is why this catch only logs.
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
     * Isolated: a failure is logged and never stops a chunk write.
     */
    private void scanResumeChunk(ScanTask scan, Storages regions, Storages entities) {
        boolean region = scan.target() == Target.REGION;
        BiConsumer<ResourceKey<Level>, CompoundTag> observer = region ? resumeReadObserver : resumeEntityReadObserver;
        if (observer == null) {
            return;
        }
        IOWorker storage = (region ? regions : entities).storageFor(scan.dimension());
        if (storage == null) {
            return;
        }
        try {
            CompoundTag onDisk = storage.load(scan.pos());
            if (onDisk != null) {
                observer.accept(scan.dimension(), onDisk);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("chunk {} recovered-coverage scan failed", scan.pos(), e);
        }
    }

    private static void bestEffort(Preflight step, String what) {
        try {
            step.run();
        } catch (Throwable e) {
            LOGGER.warn("a finalize step failed ({}); the openable save is unaffected", what, e);
        }
    }

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
     * A failed open is retried on the dimension's next task rather than remembered, because the failure can be of the
     * moment: a dimension written off on one chunk would cost every later chunk it holds for the rest of the download.
     * What is remembered is the LOG, one stack per dimension, since one root cause fails every chunk of that dimension
     * alike and a stack per chunk buries it under as many copies as the dimension is wide.
     */
    private static final class Storages {
        private final StorageOpener opener;
        private final String target;
        private final Map<ResourceKey<Level>, IOWorker> open = new LinkedHashMap<>();
        private final Set<ResourceKey<Level>> loggedFailures = new HashSet<>();

        private Storages(StorageOpener opener, String target) {
            this.opener = opener;
            this.target = target;
        }

        /**
         * Null is the whole reason this returns rather than throws: an opener that threw out of the drain loop would
         * skip the finalize, so the caller counts the dropped task and the drain runs on to the finalize.
         */
        private @Nullable IOWorker storageFor(ResourceKey<Level> dimension) {
            IOWorker storage = open.get(dimension);
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
         * What the catch is for is the step behind it, the level.dat write, which a throw here would skip.
         */
        private void synchronizeAll() {
            for (Map.Entry<ResourceKey<Level>, IOWorker> entry : open.entrySet()) {
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
