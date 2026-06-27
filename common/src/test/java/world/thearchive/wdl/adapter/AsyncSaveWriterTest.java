// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The off-main-thread save writer drained against a real {@link SimpleRegionStorage}: chunk encode thunks submitted
 * from the (test) main thread are resolved on the writer's own thread, the finalizer (the level.dat stand-in) runs
 * there too, and the completion future reports the per-target tallies. This is the headless half of the
 * no-render-freeze contract; the live freeze itself is not exercised headless.
 */
class AsyncSaveWriterTest {
    /** What a capture with nothing to carry forward supplies: an on-disk prior contributes nothing to the write. */
    private static final RegionChunkWriter.ChunkReadMerge NO_CARRY_FORWARD = (onDisk, fresh) -> 0;

    private final ChunkCodec codec = new ChunkCodecImpl();

    private static SimpleRegionStorage storage(Path directory, String type) {
        return new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, type), directory,
                DataFixers.getDataFixer(), false,
                "entities".equals(type) ? DataFixTypes.ENTITY_CHUNK : DataFixTypes.CHUNK);
    }

    /** The smallest payload an entities write task can carry. */
    private static CompoundTag entityChunk() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 1);
        return tag;
    }

    @Test
    void drainsSubmittedChunksOnTheWriterThreadAndReportsTallies(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean finalized = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},                                                  // preflight
                (chunksFailed, entityChunksFailed) -> finalized.set(true), // the level.dat write stand-in
                () -> null,                                                // the finish-time output
                () -> {}, new SaveProgress());                             // the LevelStorageAccess close stand-in

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(31, 31),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain succeeded");
        assertEquals(2, result.chunksWritten());
        assertEquals(0, result.chunksFailed());
        assertEquals(0, result.entityChunksWritten());
        assertTrue(finalized.get(), "the finalizer (level.dat) ran on the writer thread after the drain");

        try (SimpleRegionStorage in = storage(region, "chunk")) {
            assertTrue(in.read(new ChunkPos(0, 0)).join().isPresent(), "submitted chunk reached disk");
            assertTrue(in.read(new ChunkPos(31, 31)).join().isPresent(), "submitted chunk reached disk");
        }
    }

    /**
     * The open is the one step of a write task that vanilla performs outside the writer's per-chunk isolation: the
     * band's opener creates the dimension's folder eagerly, so a directory-creation failure surfaces as an
     * {@link java.io.UncheckedIOException} out of the drain loop. Unguarded it would skip both the storage synchronize
     * and the finalize, leaving the chunks that already drained on disk in a folder with no level.dat, which is a save
     * the player cannot open at all. The counting unit is deliberately the chunk and not the dimension: every task the
     * unopenable dimension drops is terrain the reopened world does not have.
     */
    @Test
    void countsEveryChunkOfAnUnopenableDimensionAndStillFinalizes(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);
        AtomicInteger finalizedEntityChunksFailed = new AtomicInteger(-1);
        AtomicInteger netherOpens = new AtomicInteger();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    if (Level.NETHER.equals(dimension)) {
                        netherOpens.incrementAndGet();
                        throw new UncheckedIOException(new IOException("DIM-1/region could not be created"));
                    }
                    return storage(region, "chunk");
                },
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {
                    finalizedChunksFailed.set(chunksFailed);
                    finalizedEntityChunksFailed.set(entityChunksFailed);
                },
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.NETHER, new ChunkPos(1, 1),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(2, 2),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(2, finalizedChunksFailed.get(),
                "the finalizer ran, so the save has the level.dat that makes it openable, and it was handed the "
                        + "same tally the future carries, since what stamps the outcome reads that one");
        assertEquals(0, finalizedEntityChunksFailed.get(), "with nothing charged to the sibling target");
        assertFalse(result.failed(), "one dimension's folder is not the save, so the drain is not aborted");
        assertEquals(2, result.chunksFailed(), "both chunks the unopenable dimension dropped are counted lost");
        assertEquals(0, result.entityChunksFailed(), "and neither is double-counted against the entities tally");
        assertEquals(1, result.chunksWritten(), "and the dimension that did open still wrote its own chunk");
        assertEquals(2, netherOpens.get(),
                "the open is retried per chunk rather than written off, since the failure can be of the moment");
        try (SimpleRegionStorage in = storage(region, "chunk")) {
            assertTrue(in.read(new ChunkPos(2, 2)).join().isPresent(),
                    "the openable dimension's chunk reached disk past the failure");
        }
    }

    /** The entities sibling of the same open, which counts into its own tally rather than the chunk one. */
    @Test
    void countsEveryEntityChunkOfAnUnopenableDimensionAndStillFinalizes(@TempDir Path save) throws Exception {
        Path entities = Files.createDirectories(save.resolve("entities"));
        AtomicBoolean finalized = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                dimension -> {
                    if (Level.NETHER.equals(dimension)) {
                        throw new UncheckedIOException(new IOException("DIM-1/entities could not be created"));
                    }
                    return storage(entities, "entities");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalized.set(true),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitEntity(Level.NETHER, new ChunkPos(0, 0), entityChunk());
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(1, 1), entityChunk());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertTrue(finalized.get(), "the finalizer ran, so the folder still gets its level.dat");
        assertFalse(result.failed());
        assertEquals(1, result.entityChunksFailed(), "the unopenable dimension's entity chunk is counted lost");
        assertEquals(0, result.chunksFailed(), "and it is not charged to the terrain tally");
        assertEquals(1, result.entityChunksWritten(), "the dimension that did open still wrote its own");
    }

    @Test
    void reportsTheChunkDrainPhaseToTheProgressSink(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        SaveProgress progress = new SaveProgress();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                progress);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(31, 31),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(SaveStage.WRITING_CHUNKS, progress.stage(), "the drain reports the chunks phase");
        assertEquals(1.0f, progress.fraction(), 1.0e-6f, "every submitted task drained, so the phase reads complete");
    }

    @Test
    void drainsChunksAndEntitiesToSeparateStoragesTalliedApart(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        Path entities = Files.createDirectories(save.resolve("entities"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> storage(entities, "entities"),
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(2, 2),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(2, 2), entityChunk());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(1, result.chunksWritten(), "the terrain chunk went to region/");
        assertEquals(1, result.entityChunksWritten(), "the entity chunk went to entities/");

        try (SimpleRegionStorage in = storage(entities, "entities")) {
            assertTrue(in.read(new ChunkPos(2, 2)).join().isPresent(), "the entity chunk reached entities/");
        }
    }

    @Test
    void routesEachDimensionToItsOwnStorage(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path overworldRegion = Files.createDirectories(save.resolve("region"));
        Path netherRegion = Files.createDirectories(save.resolve("DIM-1").resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(dimension == Level.NETHER ? netherRegion : overworldRegion, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        // The same chunk position in two dimensions: with a single storage these would collide; the writer
        // opens a storage per dimension on demand so each lands in its own folder.
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(2, result.chunksWritten(), "both dimensions' same-position chunks were written");
        try (SimpleRegionStorage in = storage(overworldRegion, "chunk")) {
            assertTrue(in.read(new ChunkPos(0, 0)).join().isPresent(), "the overworld chunk reached region/");
        }
        try (SimpleRegionStorage in = storage(netherRegion, "chunk")) {
            assertTrue(in.read(new ChunkPos(0, 0)).join().isPresent(), "the nether chunk reached DIM-1/region/");
        }
    }

    @Test
    void offThreadChunkEncodeIsByteIdenticalToOnThreadEncode(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path overworldRegion = Files.createDirectories(save.resolve("region"));
        Path netherRegion = Files.createDirectories(save.resolve("DIM-1").resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(dimension == Level.NETHER ? netherRegion : overworldRegion, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        // The same snapshot encoded two ways through the same region pipeline: the thunk resolved on the
        // writer thread and a direct on-thread encode. The disk results must be identical.
        ChunkSnapshotSource snapshot = SyntheticChunks.full(registries, true);
        CompoundTag onThread = codec.encode(snapshot, registries, false);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> codec.encode(snapshot, registries, false),
                NO_CARRY_FORWARD);
        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0), () -> onThread, NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        CompoundTag offThreadOnDisk;
        CompoundTag onThreadOnDisk;
        try (SimpleRegionStorage in = storage(overworldRegion, "chunk")) {
            offThreadOnDisk = in.read(new ChunkPos(0, 0)).join().orElseThrow();
        }
        try (SimpleRegionStorage in = storage(netherRegion, "chunk")) {
            onThreadOnDisk = in.read(new ChunkPos(0, 0)).join().orElseThrow();
        }
        assertEquals(onThreadOnDisk, offThreadOnDisk,
                "the writer-thread encode is byte-identical to the main-thread encode");
    }

    @Test
    void aThrowingEncodeThunkIsIsolatedToItsChunk(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean finalized = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalized.set(true),
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(1, 1), () -> {
            throw new RuntimeException("encode blew up for this one chunk");
        }, NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one chunk's encode throwing must not abort the whole save");
        assertEquals(1, result.chunksWritten(), "the good chunk still reached disk");
        assertEquals(1, result.chunksFailed(), "the throwing chunk is counted failed and skipped");
        assertTrue(result.chunksFailed() > 0 && !result.failed(),
                "a soft chunk loss with no hard error is a partial finish; the aggregate must not read it as clean");
        assertTrue(finalized.get(), "finish() still completed past the isolated failure");

        try (SimpleRegionStorage in = storage(region, "chunk")) {
            assertTrue(in.read(new ChunkPos(0, 0)).join().isPresent(), "the good chunk landed");
            assertFalse(in.read(new ChunkPos(1, 1)).join().isPresent(), "the throwing chunk wrote nothing");
        }
    }

    /**
     * The region target's write-failure tally: the encode succeeds and the storage write is what fails, which is the
     * only route to a {@code FAILED} outcome ({@link RegionChunkWriter#writeMerging} turns a read failure and a
     * carry-forward merge failure alike into a preserve). An uncounted loss here is a whole chunk missing from a
     * download that still reports itself complete.
     */
    @Test
    void aChunkWhoseStorageWriteThrowsIsCountedFailed(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk"),
                        region, DataFixers.getDataFixer(), false, DataFixTypes.CHUNK) {
                    @Override
                    public CompletableFuture<Void> write(ChunkPos pos, CompoundTag tag) {
                        throw new IllegalStateException("the region store rejected the write");
                    }
                },
                dimension -> {
                    throw new AssertionError("no entity chunk was submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one chunk's write failing must not abort the save");
        assertEquals(0, result.chunksWritten(), "nothing reached the region store");
        assertEquals(1, result.chunksFailed(),
                "the lost chunk is counted, so the session's finish can read the save partial");
        assertEquals(0, result.entityChunksFailed(), "and it is counted apart from the entity tally");
    }

    /**
     * The entities target's write-failure tally, which nothing else reaches. The encode-throw arm counted for a region
     * chunk cannot happen for entities: {@link AsyncSaveWriter#submitEntity} wraps an already-built tag in a supplier
     * that cannot throw, and it is the only place an entities write task is made. So the storage write is where an
     * entity chunk is lost, and an uncounted loss there is a download missing a whole chunk's mobs that still reports
     * itself complete.
     */
    @Test
    void anEntityChunkWhoseStorageWriteThrowsIsCountedFailed(@TempDir Path save) throws Exception {
        Path entities = Files.createDirectories(save.resolve("entities"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                dimension -> new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, "entities"),
                        entities, DataFixers.getDataFixer(), false, DataFixTypes.ENTITY_CHUNK) {
                    @Override
                    public CompletableFuture<Void> write(ChunkPos pos, CompoundTag tag) {
                        throw new IllegalStateException("the entities store rejected the write");
                    }
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one entity chunk's write failing must not abort the save");
        assertEquals(0, result.entityChunksWritten(), "nothing reached the entities store");
        assertEquals(1, result.entityChunksFailed(), "the lost entity chunk is counted");
        assertEquals(0, result.chunksFailed(), "and it is counted apart from the terrain tally");
    }

    @Test
    void preflightRunsBeforeAnyChunkReachesStorage(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger order = new AtomicInteger();
        AtomicInteger preflightAt = new AtomicInteger(-1);
        AtomicInteger firstStorageOpenAt = new AtomicInteger(-1);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    firstStorageOpenAt.set(order.getAndIncrement());
                    return storage(region, "chunk");
                },
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> preflightAt.set(order.getAndIncrement()),
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(0, preflightAt.get(), "preflight runs first of all, before the drain opens any storage");
        assertTrue(preflightAt.get() < firstStorageOpenAt.get(),
                "the pre-write step runs before any chunk is written into the folder");
    }

    @Test
    void outputsRunAfterTheStoragesAndAccessAreClosed(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger order = new AtomicInteger();
        AtomicInteger finalizerAt = new AtomicInteger(-1);
        AtomicInteger accessCloseAt = new AtomicInteger(-1);
        AtomicInteger outputsAt = new AtomicInteger(-1);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizerAt.set(order.getAndIncrement()),
                () -> {
                    outputsAt.set(order.getAndIncrement());
                    return null;
                },
                () -> accessCloseAt.set(order.getAndIncrement()), new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertTrue(finalizerAt.get() < accessCloseAt.get(), "the level.dat finalizer runs before the folder closes");
        assertTrue(accessCloseAt.get() < outputsAt.get(),
                "the finish-time output runs after the folder is fully written and closed");
    }

    @Test
    void reportsTheWrittenZipFileNameFromTheOutputsStep(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> "world.zip",
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals("world.zip", result.zipFileName(),
                "the outputs step's written file name rides the result to the completion surfaces");
    }

    @Test
    void aThrowingOutputsHookNeverFailsTheOpenableSave(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> {
                    throw new RuntimeException("disk full while writing the finish-time output");
                },
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "a failed finalize-output never fails the openable save");
        assertEquals(1, result.chunksWritten(), "the chunk still reached disk regardless of that outcome");
        assertNull(result.zipFileName(), "a throwing output step reports no file name");
    }

    @Test
    void aThrowingPreflightHookNeverAbortsTheDrain(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {
                    throw new RuntimeException("the pre-write step blew up");
                },
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "a failed pre-write step never aborts the save");
        assertEquals(1, result.chunksWritten(), "the drain still ran past the failed preflight");
        try (SimpleRegionStorage in = storage(region, "chunk")) {
            assertTrue(in.read(new ChunkPos(0, 0)).join().isPresent(), "the chunk still reached disk");
        }
    }

    @Test
    void outputsDoNotRunWhenTheSaveFailed(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean outputsRan = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {
                    throw new RuntimeException("the level.dat write failed"); // the finalizer fails -> the save fails
                },
                () -> {
                    outputsRan.set(true); // the finish-time output must NOT run on a failed save
                    return null;
                },
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertTrue(result.failed(), "the finalizer failure aborts the save");
        assertFalse(outputsRan.get(), "a failed save never reaches the finish-time output");
        assertNull(result.zipFileName(), "a failed save reports no file name");
    }

    @Test
    void aResumedChunkLandsInTheRecapturedBucketApartFromNew(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter first = newWriter(region);
        first.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AsyncSaveWriter second = newWriter(region);
        second.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        second.submitChunk(Level.OVERWORLD, new ChunkPos(1, 1),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = second.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(1, result.chunksNew(), "the position with no prior is a fresh write");
        assertEquals(1, result.chunksRecaptured(),
                "the position with an on-disk prior is a re-capture, not a new write");
    }

    /**
     * A real storage whose on-disk read or whose flush can be made to fail, the two disk faults the drain has to
     * survive without taking the level.dat write down with them. Everything else is the vanilla behavior, so a chunk
     * written through it really does reach the region file.
     */
    private static final class FaultyStorage extends SimpleRegionStorage {
        private final boolean failRead;
        private final boolean failFlush;

        private FaultyStorage(Path directory, boolean failRead, boolean failFlush) {
            super(new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk"), directory, DataFixers.getDataFixer(),
                    false, DataFixTypes.CHUNK);
            this.failRead = failRead;
            this.failFlush = failFlush;
        }

        @Override
        public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos) {
            return failRead
                    ? CompletableFuture.failedFuture(new IOException("the region read failed"))
                    : super.read(pos);
        }

        @Override
        public CompletableFuture<Void> synchronize(boolean flush) {
            return failFlush
                    ? CompletableFuture.failedFuture(new IOException("the region flush failed"))
                    : super.synchronize(flush);
        }
    }

    /**
     * A read failure makes the writer keep whatever is already on disk rather than overwrite it, which is the right
     * call and still loses this session's capture of that chunk. It is counted exactly as a failed write is, because
     * from the save's point of view the two are the same event. The on-disk assertion is not redundant with the tally:
     * a tally alone stays green if the writer counts a preserve and overwrites anyway.
     */
    @Test
    void aChunkPreservedWhenTheReadFailsIsCountedLost(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        CompoundTag prior = codec.encode(SyntheticChunks.full(registries, true), registries, false);
        try (SimpleRegionStorage seed = storage(region, "chunk")) {
            seed.write(new ChunkPos(0, 0), prior).join();
            seed.synchronize(true).join();
        }
        CompoundTag onDiskBefore;
        try (SimpleRegionStorage in = storage(region, "chunk")) {
            onDiskBefore = in.read(new ChunkPos(0, 0)).join().orElseThrow();
        }

        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);
        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new FaultyStorage(region, true, false),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, false), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(1, result.chunksFailed(), "the capture never reached the save, so it counts like a failure");
        assertEquals(0, result.chunksWritten(), "and it is not counted as written");
        assertEquals(1, finalizedChunksFailed.get(),
                "the finalize sees it too, so nothing downstream can stamp this download clean");
        try (SimpleRegionStorage in = storage(region, "chunk")) {
            assertEquals(onDiskBefore, in.read(new ChunkPos(0, 0)).join().orElseThrow(),
                    "the prior the writer refused to overwrite is still on disk, unchanged");
        }
    }

    /**
     * The other half of the same split, and the reason it is a split: a chunk with no content of its own is also not
     * written, and counting that as a loss would report an intact download partial.
     */
    @Test
    void aChunkWithNothingToWriteIsNotCountedLost(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = newWriter(region);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> null, NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(0, result.chunksFailed(), "nothing was lost, so the download still reads clean");
        assertEquals(0, result.chunksWritten(), "and nothing was written either");
    }

    /**
     * The flush is the last step before the level.dat write, and vanilla's is a channel force. Letting a failure
     * through would cost the folder its level.dat and leave a world that does not open at all, which is a far worse
     * trade than the durability doubt a failed force leaves. Nothing is counted for it, and that is the other half of
     * the contract: the close that follows forces every region file again, so the failure this catch sees is usually
     * resolved a statement later, and a download whose bytes all reached the file system must not report itself partial
     * over a promise this project does not make.
     */
    @Test
    void aStorageThatCannotBeFlushedIsNotCountedAndTheSaveStillFinalizes(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new FaultyStorage(region, false, true),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), NO_CARRY_FORWARD);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(0, finalizedChunksFailed.get(),
                "the finalizer ran, so the folder has its level.dat, and it was told of no lost chunk");
        assertFalse(result.failed(), "a flush that could not be proven is not a failed save");
        assertEquals(1, result.chunksWritten(), "the chunk was still handed to the file system");
        assertEquals(0, result.chunksFailed(), "so the download reports clean, since nothing missed the save");
    }

    @Test
    void theWriterAppliesTheReadMergeEachSubmitSupplied(@TempDir Path save) throws Exception {
        // Every other case here passes the same merge, so none of them would notice the writer ignoring the one
        // each submit supplied and applying a fixed merge of its own instead.
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        int[] applied = { 0 };
        RegionChunkWriter.ChunkReadMerge recording = (onDisk, fresh) -> {
            applied[0]++;
            return 0;
        };

        AsyncSaveWriter first = newWriter(region);
        first.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), recording);
        first.finish().get(30, TimeUnit.SECONDS);
        assertEquals(0, applied[0], "no prior on disk, so no read-merge runs");

        AsyncSaveWriter second = newWriter(region);
        second.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), recording);
        second.finish().get(30, TimeUnit.SECONDS);

        assertEquals(1, applied[0], "the second write reads the prior and applies the merge THIS submit supplied");
    }

    /** A writer over one region directory, for the cases that only need chunks to reach disk. */
    private AsyncSaveWriter newWriter(Path region) {
        return new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted");
                },
                () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
    }
}
