// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPosOrNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SaveStage;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.LogCapture;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

class AsyncSaveWriterTest {
    private static final String WRITER_LOGGER = "world.thearchive.wdl.adapter.AsyncSaveWriter";

    private final ChunkCodec codec = new ChunkCodecImpl();

    private static IOWorker storage(Path directory, String type) {
        return new TestRegionStorage(directory, false, type);
    }

    private static class TestRegionStorage extends IOWorker {
        private TestRegionStorage(Path directory, boolean sync, String name) {
            super(directory, sync, name);
        }
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
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalized.set(true),
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(31, 31),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain succeeded");
        assertEquals(2, result.chunksWritten());
        assertEquals(0, result.chunksFailed());
        assertEquals(0, result.entityChunksWritten());
        assertTrue(finalized.get(), "the finalizer ran on the writer thread after the drain");

        try (IOWorker in = storage(region, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(0, 0)).join().isPresent(), "submitted chunk reached disk");
            assertTrue(in.loadAsync(new ChunkPos(31, 31)).join().isPresent(), "submitted chunk reached disk");
        }
    }

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
                        throw new UncheckedIOException(new IOException("nether region folder could not be created"));
                    }
                    return storage(region, "chunk");
                },
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {
                    finalizedChunksFailed.set(chunksFailed);
                    finalizedEntityChunksFailed.set(entityChunksFailed);
                },
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.NETHER, new ChunkPos(1, 1),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(2, 2),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(2, finalizedChunksFailed.get(),
                "the finalizer ran, so the save has the level.dat that makes it openable, and it was handed the "
                        + "same tally the future carries, since the completion record it stamps reads that one");
        assertEquals(0, finalizedEntityChunksFailed.get(), "with nothing charged to the sibling target");
        assertFalse(result.failed(), "one dimension's folder is not the save, so the drain is not aborted");
        assertEquals(2, result.chunksFailed(), "both chunks the unopenable dimension dropped are counted lost");
        assertEquals(0, result.entityChunksFailed(), "and neither is double-counted against the entities tally");
        assertEquals(1, result.chunksWritten(), "and the dimension that did open still wrote its own chunk");
        assertEquals(2, netherOpens.get(),
                "the open is retried per chunk rather than written off, since the failure can be of the moment");
        try (IOWorker in = storage(region, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(2, 2)).join().isPresent(),
                    "the openable dimension's chunk reached disk past the failure");
        }
    }

    @Test
    void countsEveryEntityChunkOfAnUnopenableDimensionAndStillFinalizes(@TempDir Path save) throws Exception {
        Path entities = Files.createDirectories(save.resolve("entities"));
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);
        AtomicInteger finalizedEntityChunksFailed = new AtomicInteger(-1);
        AtomicInteger netherOpens = new AtomicInteger();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                dimension -> {
                    if (Level.NETHER.equals(dimension)) {
                        netherOpens.incrementAndGet();
                        throw new UncheckedIOException(new IOException("nether entities folder could not be created"));
                    }
                    return storage(entities, "entities");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {
                    finalizedChunksFailed.set(chunksFailed);
                    finalizedEntityChunksFailed.set(entityChunksFailed);
                },
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitEntity(Level.NETHER, new ChunkPos(0, 0), pigChunk(new ChunkPos(0, 0)));
        writer.submitEntity(Level.NETHER, new ChunkPos(1, 1), pigChunk(new ChunkPos(1, 1)));
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(2, 2), pigChunk(new ChunkPos(2, 2)));
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(2, finalizedEntityChunksFailed.get(),
                "the finalizer ran, so the save has the level.dat that makes it openable, and it was handed the "
                        + "same tally the future carries");
        assertEquals(0, finalizedChunksFailed.get(), "with nothing charged to the sibling target");
        assertFalse(result.failed(), "one dimension's folder is not the save, so the drain is not aborted");
        assertEquals(2, result.entityChunksFailed(), "both entity-chunks the unopenable dimension dropped are lost");
        assertEquals(0, result.chunksFailed(), "and neither is double-counted against the region tally");
        assertEquals(1, result.entityChunksWritten(), "and the dimension that did open still wrote its own");
        assertEquals(2, netherOpens.get(),
                "the open is retried per entity-chunk rather than written off, since the failure can be of "
                        + "the moment");
    }

    @Test
    void anUnopenableDimensionsRewriteIsSkippedAndTheSaveStillFinalizes(@TempDir Path save) throws Exception {
        AtomicBoolean finalized = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new UncheckedIOException(new IOException("nether region folder could not be created"));
                },
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalized.set(true),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunkRewrite(Level.NETHER, new ChunkPos(0, 0),
                onDisk -> {
                    throw new AssertionError("the storage never opened, so no chunk can be folded into");
                });
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertTrue(finalized.get(), "the finalizer still ran, so the save has the level.dat that makes it openable");
        assertFalse(result.failed(), "and the drain was not aborted");
        assertEquals(0, result.mergedContainers(), "the fold reached no block entity");
        assertEquals(0, result.chunksFailed(),
                "and the rewrite takes no tally, as it takes none for a position with no prior on disk");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                progress);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(31, 31),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(SaveStage.WRITING_CHUNKS, progress.stage(), "the drain reports the chunks phase");
        assertEquals(1.0f, progress.fraction(), 1.0e-6f, "every submitted task drained, so the phase reads complete");
    }

    @Test
    void reportsTheMapWritePhaseOverTheBatchAfterTheChunkDrain(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        SaveProgress progress = new SaveProgress();
        List<String> order = new ArrayList<>();
        AtomicReference<SaveStage> stageAtFirstWrite = new AtomicReference<>();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> order.add("finalize"),
                () -> null,
                () -> {},
                progress);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            order.add("chunk");
            return codec.encode(SyntheticChunks.full(registries, true), registries, false);
        }, ChunkMerge::merge);
        writer.submitMapBatch(List.of(
                () -> {
                    stageAtFirstWrite.set(progress.stage());
                    order.add("map");
                },
                () -> order.add("map"),
                () -> order.add("map")));
        writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(List.of("chunk", "map", "map", "map", "finalize"), order,
                "the batch drains after every chunk write and before the finalize");
        assertEquals(SaveStage.WRITING_MAPS, stageAtFirstWrite.get(), "the phase is relabeled before the first write");
        assertEquals(SaveStage.WRITING_MAPS, progress.stage(), "the drain ends in the maps phase");
        assertEquals(1.0f, progress.fraction(), 1.0e-6f, "every batched write reported, so the phase reads complete");
    }

    @Test
    void isolatesOneFailingMapWriteFromTheRestOfTheBatch(@TempDir Path save) throws Exception {
        SaveProgress progress = new SaveProgress();
        AtomicInteger attempted = new AtomicInteger();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunk was submitted, so the region storage must not open");
                },
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                progress);

        writer.submitMapBatch(List.of(
                () -> {
                    attempted.incrementAndGet();
                    throw new IllegalStateException("this map's write failed");
                },
                attempted::incrementAndGet));
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one map's write failure never fails the save");
        assertEquals(2, attempted.get(), "the batch continues past a failing write");
        assertEquals(1.0f, progress.fraction(), 1.0e-6f, "the failed write still advances the phase");
    }

    @Test
    void skipsTheMapPhaseWhenNothingWasBatched(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        SaveProgress progress = new SaveProgress();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                progress);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitMapBatch(List.of());
        writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(SaveStage.WRITING_CHUNKS, progress.stage(), "an empty batch publishes no maps phase");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(2, 2),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        CompoundTag entityChunk = new CompoundTag();
        entityChunk.putInt("DataVersion", 1);
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(2, 2), entityChunk);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(1, result.chunksWritten(), "the terrain chunk went to region/");
        assertEquals(1, result.entityChunksWritten(), "the entity chunk went to entities/");

        try (IOWorker in = storage(entities, "entities")) {
            assertTrue(in.loadAsync(new ChunkPos(2, 2)).join().isPresent(), "the entity chunk reached entities/");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(2, result.chunksWritten(), "both dimensions' same-position chunks were written");
        try (IOWorker in = storage(overworldRegion, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(0, 0)).join().isPresent(), "the overworld chunk reached region/");
        }
        try (IOWorker in = storage(netherRegion, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(0, 0)).join().isPresent(), "the nether chunk reached DIM-1/region/");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        ChunkSnapshotSource snapshot = SyntheticChunks.full(registries, true);
        CompoundTag onThread = codec.encode(snapshot, registries, false);
        AtomicReference<Thread> encodedOn = new AtomicReference<>();
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            encodedOn.set(Thread.currentThread());
            return codec.encode(snapshot, registries, false);
        }, ChunkMerge::merge);
        writer.submitChunk(Level.NETHER, new ChunkPos(0, 0), () -> onThread, ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertNotNull(encodedOn.get(), "the encode thunk was never resolved");
        assertEquals("wdl-save-writer", encodedOn.get().getName(),
                "the chunk encode must be deferred to the writer thread, not run at submit on the caller's");
        CompoundTag offThreadOnDisk;
        CompoundTag onThreadOnDisk;
        try (IOWorker in = storage(overworldRegion, "chunk")) {
            offThreadOnDisk = in.loadAsync(new ChunkPos(0, 0)).join().orElseThrow();
        }
        try (IOWorker in = storage(netherRegion, "chunk")) {
            onThreadOnDisk = in.loadAsync(new ChunkPos(0, 0)).join().orElseThrow();
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalized.set(true),
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(1, 1), () -> {
            throw new RuntimeException("encode blew up for this one chunk");
        }, ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one chunk's encode throwing must not abort the whole save");
        assertEquals(1, result.chunksWritten(), "the good chunk still reached disk");
        assertEquals(1, result.chunksFailed(), "the throwing chunk is counted failed and skipped");
        assertTrue(result.chunksFailed() > 0 && !result.failed(),
                "a soft chunk loss with no hard error is a partial finish; the aggregate must not read it as clean");
        assertTrue(finalized.get(), "finish() still completed past the isolated failure");

        try (IOWorker in = storage(region, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(0, 0)).join().isPresent(), "the good chunk landed");
            assertFalse(in.loadAsync(new ChunkPos(1, 1)).join().isPresent(), "the throwing chunk wrote nothing");
        }
    }

    @Test
    void aChunkWhoseStorageWriteThrowsIsCountedFailed(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new TestRegionStorage(region, false, "chunk") {
                    @Override
                    public CompletableFuture<Void> store(ChunkPos pos, CompoundTag tag) {
                        throw new IllegalStateException("the region store rejected the write");
                    }
                },
                dimension -> {
                    throw new AssertionError("no entity chunk was submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one chunk's write failing must not abort the save");
        assertEquals(0, result.chunksWritten(), "nothing reached the region store");
        assertEquals(1, result.chunksFailed(),
                "the lost chunk is counted, so the session's finish can read the save partial");
        assertEquals(0, result.entityChunksFailed(), "and it is counted apart from the entity tally");
    }

    @Test
    void anEntityChunkWhoseStorageWriteThrowsIsCountedFailed(@TempDir Path save) throws Exception {
        Path entities = Files.createDirectories(save.resolve("entities"));

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no region chunk was submitted, so the region storage must not open");
                },
                dimension -> new TestRegionStorage(entities, false, "entities") {
                    @Override
                    public CompletableFuture<Void> store(ChunkPos pos, CompoundTag tag) {
                        throw new IllegalStateException("the entities store rejected the write");
                    }
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        CompoundTag entityChunk = new CompoundTag();
        entityChunk.putInt("DataVersion", 1);
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(2, 2), entityChunk);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "one entity chunk's write failing must not abort the save");
        assertEquals(0, result.entityChunksWritten(), "nothing reached the entities store");
        assertEquals(1, result.entityChunksFailed(),
                "the lost entity chunk is counted, so the session's finish can read the save partial");
        assertEquals(0, result.chunksFailed(), "and it is counted apart from the region tally");
    }

    @Test
    void theWriterThreadFoldMergesContainerItemsIntoTheChunk(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, true,
                List.of(blockEntity("minecraft:chest", 2, 64, 2)));
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(2, 64, 2), sink.captureItems(items, registries));

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            CompoundTag tag = codec.encode(snapshot, registries, false);
            ContainerMerge.mergeChunkStash(sink, tag, new ChunkPos(0, 0), holders);
            return tag;
        }, ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        try (IOWorker in = storage(region, "chunk")) {
            CompoundTag back = in.loadAsync(new ChunkPos(0, 0)).join().orElseThrow();
            CompoundTag chest = findByPosOrNull(back, 2, 64, 2);
            assertNotNull(chest, "the chest block entity is on disk");
            NonNullList<ItemStack> decoded = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(chest, decoded);
            assertEquals(Items.DIAMOND, decoded.get(0).getItem(), "the writer-thread fold merged the captured Items");
            assertEquals(5, decoded.get(0).getCount());
        }
    }

    @Test
    void theWriterThreadFoldMergesLecternBookIntoTheChunk(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        LecternSink sink = new LecternSinkImpl();

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, true,
                List.of(blockEntity("minecraft:lectern", 3, 64, 3)));
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(3, 64, 3), sink.captureBook(new ItemStack(Items.WRITABLE_BOOK), 4, registries));

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            CompoundTag tag = codec.encode(snapshot, registries, false);
            ContainerMerge.mergeLecternChunkStash(sink, tag, new ChunkPos(0, 0), holders);
            return tag;
        }, ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        try (IOWorker in = storage(region, "chunk")) {
            CompoundTag back = in.loadAsync(new ChunkPos(0, 0)).join().orElseThrow();
            CompoundTag lectern = findByPosOrNull(back, 3, 64, 3);
            assertNotNull(lectern, "the lectern block entity is on disk");
            assertTrue(lectern.getCompound("Book").contains("id"), "the writer-thread fold merged the Book");
            assertEquals(4, (lectern.contains("Page") ? lectern.getInt("Page") : -1), "and its Page");
        }
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(0, preflightAt.get(), "preflight runs first of all, before the drain opens any storage");
        assertTrue(preflightAt.get() < firstStorageOpenAt.get(),
                "the pre-merge backup runs before any chunk is written into the folder");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizerAt.set(order.getAndIncrement()),
                () -> {
                    outputsAt.set(order.getAndIncrement());
                    return null;
                },
                () -> accessCloseAt.set(order.getAndIncrement()), new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertTrue(finalizerAt.get() < accessCloseAt.get(), "the finalizer runs before the folder closes");
        assertTrue(accessCloseAt.get() < outputsAt.get(),
                "the export zip runs after the folder is fully written and closed");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> "world.zip",
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals("world.zip", result.zipFileName(),
                "the outputs step's written zip name rides the result to the completion surfaces");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> {
                    throw new RuntimeException("disk full while writing the export zip");
                },
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "a failed finalize-output (the zip) never fails the openable save");
        assertEquals(1, result.chunksWritten(), "the chunk still reached disk regardless of the zip outcome");
        assertNull(result.zipFileName(), "a throwing export reports no zip name");
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
                    throw new RuntimeException("the resume backup blew up");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "a failed pre-merge backup never aborts the save");
        assertEquals(1, result.chunksWritten(), "the drain still ran past the failed preflight");
        try (IOWorker in = storage(region, "chunk")) {
            assertTrue(in.loadAsync(new ChunkPos(0, 0)).join().isPresent(), "the chunk still reached disk");
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
                () -> {},
                (chunksFailed, entityChunksFailed) -> {
                    throw new RuntimeException("the completion record failed");
                },
                () -> {
                    outputsRan.set(true);
                    return null;
                },
                () -> {}, new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertTrue(result.failed(), "the finalizer failure aborts the save");
        assertFalse(outputsRan.get(), "a failed save is never zipped or sized (the export + size outputs are skipped)");
        assertNull(result.zipFileName(), "a failed save reports no zip name");
    }

    @Test
    void resumeScanReportsThePriorOnDiskChunkToTheObserver(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();

        AsyncSaveWriter first = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        ChunkSnapshotSource snapshot = SyntheticChunks.fullWithBlockEntities(registries, true,
                List.of(blockEntity("minecraft:chest", 2, 64, 2)));
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(2, 64, 2), sink.captureItems(items, registries));
        first.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            CompoundTag tag = codec.encode(snapshot, registries, false);
            ContainerMerge.mergeChunkStash(sink, tag, new ChunkPos(0, 0), holders);
            return tag;
        }, ChunkMerge::merge);
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AtomicReference<ResourceKey<Level>> observedDimension = new AtomicReference<>();
        AtomicReference<CompoundTag> observed = new AtomicReference<>();
        AsyncSaveWriter second = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        second.observeResumeReads((dimension, onDisk) -> {
            observedDimension.set(dimension);
            observed.set(onDisk);
        });
        second.submitResumeScan(Level.OVERWORLD, new ChunkPos(0, 0));
        assertFalse(second.finish().get(30, TimeUnit.SECONDS).failed());

        assertEquals(Level.OVERWORLD, observedDimension.get(), "the scan reported the chunk's dimension");
        CompoundTag onDisk = observed.get();
        assertNotNull(onDisk, "the scan handed the prior on-disk chunk to the observer");
        CompoundTag chest = findByPosOrNull(onDisk, 2, 64, 2);
        assertNotNull(chest, "the observer saw the prior chest block entity");
        assertFalse(chest.getList("Items", Tag.TAG_COMPOUND).isEmpty(), "the prior chest carries the captured Items");
    }

    @Test
    void resumeEntityScanReportsThePriorOnDiskEntityChunkToTheObserver(@TempDir Path save) throws Exception {
        TestRegistries.frozen();
        Path entities = Files.createDirectories(save.resolve("entities"));
        UUID cart = new UUID(0x1234L, 0x5678L);

        AsyncSaveWriter first = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunks were submitted, so the region storage must not open");
                },
                dimension -> storage(entities, "entities"),
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        first.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk(filledVehicle(cart)));
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AtomicReference<ResourceKey<Level>> observedDimension = new AtomicReference<>();
        AtomicReference<CompoundTag> observed = new AtomicReference<>();
        AsyncSaveWriter second = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunks were submitted, so the region storage must not open");
                },
                dimension -> storage(entities, "entities"),
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        second.observeEntityResumeReads((dimension, onDisk) -> {
            observedDimension.set(dimension);
            observed.set(onDisk);
        });
        second.submitEntityResumeScan(Level.OVERWORLD, new ChunkPos(0, 0));
        assertFalse(second.finish().get(30, TimeUnit.SECONDS).failed());

        assertEquals(Level.OVERWORLD, observedDimension.get(), "the scan reported the entity chunk's dimension");
        CompoundTag onDisk = observed.get();
        assertNotNull(onDisk, "the scan handed the prior on-disk entity chunk to the observer");
        CompoundTag vehicle = onDisk.getList("Entities", Tag.TAG_COMPOUND).getCompound(0);
        assertNotNull(vehicle.get("UUID"), "the prior container entity kept its UUID");
        assertFalse(vehicle.getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                "the prior container entity carries the captured Items");
    }

    /**
     * A second write to an already-written entity chunk read-modifies the prior on disk
     * ({@link AsyncSaveWriter#submitEntity} routes through {@link RegionChunkWriter#writeMerging} with
     * {@link EntityMerge}), so the opened contents survive in either order. Pinned here, at the real writer and
     * storage, because a change of that route to a plain overwrite would silently drop the contents and no
     * {@link EntityMerge} unit test would catch it.
     */
    @Test
    void aSecondEntityWriteToTheSameChunkReadMergesRatherThanOverwrites(@TempDir Path save) throws Exception {
        TestRegistries.frozen();
        Path entities = Files.createDirectories(save.resolve("entities"));
        UUID openedAfterFlush = new UUID(0xA1L, 0xB2L);
        UUID openedBeforeFlush = new UUID(0xC3L, 0xD4L);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunks were submitted, so the region storage must not open");
                },
                dimension -> storage(entities, "entities"),
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());

        writer.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk(emptyVehicle(openedAfterFlush)));
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk(filledVehicle(openedAfterFlush)));

        writer.submitEntity(Level.OVERWORLD, new ChunkPos(0, 1), entityChunk(filledVehicle(openedBeforeFlush)));
        writer.submitEntity(Level.OVERWORLD, new ChunkPos(0, 1), entityChunk(emptyVehicle(openedBeforeFlush)));

        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(4, result.entityChunksWritten(), "every entity-chunk write landed, first and re-flush alike");
        assertEquals(1, result.entitiesCarriedForward(),
                "exactly the one prior-contents carry (the revisit clobber case) reached the tally");
        try (IOWorker in = storage(entities, "entities")) {
            assertFalse(vehicleItems(in, new ChunkPos(0, 0), openedAfterFlush).isEmpty(),
                    "the re-flush folded the opened contents onto the on-disk vehicle (open-after-flush recovers)");
            assertFalse(vehicleItems(in, new ChunkPos(0, 1), openedBeforeFlush).isEmpty(),
                    "the empty re-flush read-merged the prior on-disk items rather than overwriting them");
        }
    }

    @Test
    void aResumedChunkLandsInTheRecapturedBucketApartFromNew(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter first = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        first.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AsyncSaveWriter second = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        second.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        second.submitChunk(Level.OVERWORLD, new ChunkPos(1, 1),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = second.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(1, result.chunksNew(), "the position with no prior is a fresh write");
        assertEquals(1, result.chunksRecaptured(),
                "the position with an on-disk prior is a re-capture, not a new write");
    }

    @Test
    void aChunkRewriteFoldsIntoThePriorOnDiskChunkAndTalliesMergedContainers(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));

        AsyncSaveWriter first = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        first.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AsyncSaveWriter second = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        second.submitChunkRewrite(Level.OVERWORLD, new ChunkPos(0, 0), onDisk -> {
            onDisk.putString("wdl_test_folded", "contents");
            return 2;
        });
        AsyncSaveWriter.SaveResult result = second.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed());
        assertEquals(2, result.mergedContainers(), "the fold count reached the merged-containers tally");
        try (IOWorker in = storage(region, "chunk")) {
            CompoundTag onDisk = in.loadAsync(new ChunkPos(0, 0)).join().orElseThrow();
            assertEquals("contents", onDisk.getString("wdl_test_folded"),
                    "the folded chunk was written back to region/");
            assertFalse(onDisk.getList("sections", Tag.TAG_COMPOUND).isEmpty(),
                    "the prior terrain survived the read-modify-write");
        }
    }

    @Test
    void anOffThreadTaskThrowIsSwallowedAndTheSaveCompletes(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean ran = new AtomicBoolean(false);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());

        writer.submit(() -> {
            ran.set(true);
            throw new IllegalStateException("an off-thread task failure the drain must absorb");
        });
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the task throw never aborts the save");
        assertEquals(1, result.chunksWritten(), "the chunk submitted after the throwing task still drained");
        assertTrue(ran.get(), "the task ran on the writer thread");
    }

    private static CompoundTag filledVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, "minecraft:diamond");
    }

    private static CompoundTag emptyVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid);
    }

    private static ListTag vehicleItems(IOWorker storage, ChunkPos pos, UUID uuid) {
        CompoundTag chunk = storage.loadAsync(pos).join().orElseThrow();
        ListTag list = chunk.getList("Entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entity = list.getCompound(i);
            if (uuid.equals(EntityMerge.readUuid(entity))) {
                return entity.getList("Items", Tag.TAG_COMPOUND);
            }
        }
        throw new AssertionError("no entity " + uuid + " in " + pos);
    }

    private static final class FaultyStorage extends TestRegionStorage {
        private final boolean failRead;
        private final boolean failFlush;

        private FaultyStorage(Path directory, String type, boolean failRead, boolean failFlush) {
            super(directory, false, type);
            this.failRead = failRead;
            this.failFlush = failFlush;
        }

        @Override
        public CompletableFuture<Optional<CompoundTag>> loadAsync(ChunkPos pos) {
            return failRead
                    ? CompletableFuture.failedFuture(new IOException("the region read failed"))
                    : super.loadAsync(pos);
        }

        @Override
        public CompletableFuture<Void> synchronize(boolean flush) {
            return failFlush
                    ? CompletableFuture.failedFuture(new IOException("the region flush failed"))
                    : super.synchronize(flush);
        }
    }

    /**
     * The on-disk assertion is not redundant with the tally: a tally alone stays green if the writer counts a preserve
     * and overwrites anyway.
     */
    @Test
    void aChunkPreservedWhenTheReadFailsIsCountedLost(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        List<BlockPos> chests = List.of(new BlockPos(2, 64, 2));
        ChunkSnapshotSource snapshot = chunkWithChests(registries, chests);
        writeOpenedChests(region, registries, snapshot, chests);

        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);
        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new FaultyStorage(region, "chunk", true, false),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(snapshot, registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(1, result.chunksFailed(), "the capture never reached the save, so it counts like a failure");
        assertEquals(0, result.chunksWritten(), "and it is not counted as written");
        assertEquals(1, finalizedChunksFailed.get(),
                "the finalize sees it too, so the completion record cannot stamp this download clean");
        try (IOWorker in = storage(region, "chunk")) {
            assertEquals(Items.DIAMOND, firstItemOf(in, registries, new ChunkPos(0, 0), 2, 64, 2).getItem(),
                    "the prior the writer refused to overwrite is still on disk");
        }
    }

    @Test
    void aChunkWithNothingToWriteIsNotCountedLost(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> null, ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(0, result.chunksFailed(), "nothing was lost, so the download still reads clean");
        assertEquals(0, result.chunksWritten(), "and nothing was written either");
        assertEquals(0, finalizedChunksFailed.get(), "which is what the completion record is stamped with");
    }

    @Test
    void anEntityChunkPreservedWhenTheReadFailsIsCountedLost(@TempDir Path save) throws Exception {
        TestRegistries.frozen();
        Path entities = Files.createDirectories(save.resolve("entities"));
        UUID parked = new UUID(0xE1L, 0xE2L);

        AsyncSaveWriter first = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunks were submitted, so the region storage must not open");
                },
                dimension -> storage(entities, "entities"),
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
        first.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk(filledVehicle(parked)));
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed());

        AtomicInteger finalizedEntityChunksFailed = new AtomicInteger(-1);
        AsyncSaveWriter second = new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("no chunks were submitted, so the region storage must not open");
                },
                dimension -> new FaultyStorage(entities, "entities", true, false),
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedEntityChunksFailed.set(entityChunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());
        second.submitEntity(Level.OVERWORLD, new ChunkPos(0, 0), entityChunk(emptyVehicle(parked)));
        AsyncSaveWriter.SaveResult result = second.finish().get(30, TimeUnit.SECONDS);

        assertEquals(1, result.entityChunksFailed(), "the capture never reached the save, so it counts lost");
        assertEquals(0, result.entityChunksWritten(), "and it is not counted as written");
        assertEquals(1, finalizedEntityChunksFailed.get(), "the finalize sees it on its own target's term");
        try (IOWorker in = storage(entities, "entities")) {
            assertFalse(vehicleItems(in, new ChunkPos(0, 0), parked).isEmpty(),
                    "the prior the writer refused to overwrite is still on disk");
        }
    }

    @Test
    void aChunkWhoseCarryForwardThrowsPartWayKeepsTheWholeOnDiskCopy(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        BlockPos carried = new BlockPos(2, 64, 2);
        BlockPos unreached = new BlockPos(3, 64, 3);
        List<BlockPos> chests = List.of(carried, unreached);
        ChunkSnapshotSource snapshot = chunkWithChests(registries, chests);
        writeOpenedChests(region, registries, snapshot, chests);

        AtomicBoolean merged = new AtomicBoolean(false);
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);
        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());
        // Reaching one chest and then throwing is the state the real merges leave, since both carry in place.
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(snapshot, registries, false),
                (onDisk, fresh) -> {
                    merged.set(true);
                    findByPos(fresh, carried.getX(), carried.getY(), carried.getZ()).put("Items",
                            findByPos(onDisk, carried.getX(), carried.getY(), carried.getZ())
                                    .getList("Items", Tag.TAG_COMPOUND).copy());
                    throw new IllegalStateException("a carry-forward that throws part-way through the chunk");
                });
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertTrue(merged.get(), "the arm under test is the merge arm, so the injected carry-forward must have run");
        try (IOWorker in = storage(region, "chunk")) {
            // Only the unreached chest can show this. The carried one holds the stack either way, because the
            // merge copied it into the fresh tag before throwing, so asserting on it would prove nothing.
            assertEquals(Items.DIAMOND, firstItemOf(in, registries, new ChunkPos(0, 0), unreached.getX(),
                    unreached.getY(), unreached.getZ()).getItem(),
                    "the chest the carry-forward never reached kept its contents, so no partial tag was written");
        }
        assertEquals(0, result.chunksWritten(), "the half-merged tag was not written");
        assertEquals(1, result.chunksFailed(), "and the capture that went nowhere is counted lost");
        assertEquals(1, finalizedChunksFailed.get(), "so the completion record cannot stamp this download clean");
    }

    private static ChunkSnapshotSource chunkWithChests(RegistryAccess.Frozen registries, List<BlockPos> chests) {
        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockPos chest : chests) {
            blockEntities.add(blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ()));
        }
        return SyntheticChunks.fullWithBlockEntities(registries, true, blockEntities);
    }

    private void writeOpenedChests(Path region, RegistryAccess.Frozen registries, ChunkSnapshotSource snapshot,
            List<BlockPos> chests) throws Exception {
        ContainerSink sink = new ContainerSinkImpl();
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        for (BlockPos chest : chests) {
            holders.put(chest, sink.captureItems(items, registries));
        }
        AsyncSaveWriter writer = newWriter(region);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            CompoundTag tag = codec.encode(snapshot, registries, false);
            ContainerMerge.mergeChunkStash(sink, tag, new ChunkPos(0, 0), holders);
            return tag;
        }, ChunkMerge::merge);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed());
        CompoundTag revisit = codec.encode(snapshot, registries, false);
        try (IOWorker in = storage(region, "chunk")) {
            for (BlockPos chest : chests) {
                assertEquals(Items.DIAMOND, firstItemOf(in, registries, new ChunkPos(0, 0), chest.getX(),
                        chest.getY(), chest.getZ()).getItem(),
                        "the prior session's stack reached the region file at " + chest);
                assertTrue(findByPos(revisit, chest.getX(), chest.getY(), chest.getZ())
                        .getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                        "the fold must not write back into the snapshot at " + chest + ", or a revisit would "
                                + "re-capture the prior's contents and every on-disk assertion here is vacuous");
            }
        }
    }

    private static ItemStack firstItemOf(IOWorker storage, RegistryAccess registries, ChunkPos pos,
            int x, int y, int z) {
        CompoundTag chunk = storage.loadAsync(pos).join().orElseThrow();
        CompoundTag blockEntity = findByPosOrNull(chunk, x, y, z);
        assertNotNull(blockEntity, "no block entity at " + x + "/" + y + "/" + z + " in " + pos);
        NonNullList<ItemStack> decoded = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(blockEntity, decoded);
        return decoded.get(0);
    }

    @Test
    void aStorageThatCannotBeFlushedIsNotCountedAndTheSaveStillFinalizes(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicInteger finalizedChunksFailed = new AtomicInteger(-1);

        AsyncSaveWriter writer = new AsyncSaveWriter(
                dimension -> new FaultyStorage(region, "chunk", false, true),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> finalizedChunksFailed.set(chunksFailed),
                () -> null,
                () -> {},
                new SaveProgress());

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0),
                () -> codec.encode(SyntheticChunks.full(registries, true), registries, false), ChunkMerge::merge);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(0, finalizedChunksFailed.get(),
                "the finalizer ran, so the folder has its level.dat, and it was told of no lost chunk");
        assertFalse(result.failed(), "a flush that could not be proven is not a failed save");
        assertEquals(1, result.chunksWritten(), "the chunk was still handed to the file system");
        assertEquals(0, result.chunksFailed(), "so the download reports clean, since nothing missed the save");
    }

    private static CompoundTag pigChunk(ChunkPos pos) {
        return EntityFixtures.entityChunkTagAt(pos, EntityFixtures.entityTag("minecraft:pig"));
    }

    private static CompoundTag entityChunk(CompoundTag... entities) {
        return EntityFixtures.entityChunkTagWith(entities);
    }

    @Test
    void theWriterAppliesTheReadMergeEachSubmitSupplied(@TempDir Path save) throws Exception {
        // Production's only merge is a closure built per chunk; if it were dropped the carry-forward would silently
        // fall back to its unknown-everything behavior.
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

    /**
     * The loader can rebuild the client's block registry in place when a level closes, so a block is unresolvable while
     * the rebuild runs. The writer must resolve no encode across that window.
     */
    @Test
    void resolvesNoEncodeWhileEncodingIsPaused(@TempDir Path save) throws Exception {
        RegistryAccess registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean loaderStateAvailable = new AtomicBoolean(true);
        AtomicInteger encodesDuringRebuild = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();

        AsyncSaveWriter writer = newWriter(region);
        CountDownLatch firstEncoded = new CountDownLatch(1);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(5, 5), () -> {
            encodes.incrementAndGet();
            firstEncoded.countDown();
            return codec.encode(SyntheticChunks.full(registries, true), registries, false);
        }, ChunkMerge::merge);
        assertTrue(firstEncoded.await(30, TimeUnit.SECONDS), "the writer is live and consuming the queue");

        writer.pauseEncoding();
        loaderStateAvailable.set(false);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            encodes.incrementAndGet();
            if (!loaderStateAvailable.get()) {
                encodesDuringRebuild.incrementAndGet();
            }
            return codec.encode(SyntheticChunks.full(registries, true), registries, false);
        }, ChunkMerge::merge);

        for (int i = 0; i < 50 && encodes.get() == 1; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, encodes.get(), "the same writer resolves nothing more once it is held");

        loaderStateAvailable.set(true);
        writer.resumeEncoding();

        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain succeeded");
        assertEquals(2, result.chunksWritten(), "both chunks reach disk, the held one after the rebuild");
        assertEquals(0, encodesDuringRebuild.get(), "no encode ran while the loader state was unavailable");
    }

    @Test
    void pauseWaitsForAnEncodeAlreadyInFlight(@TempDir Path save) throws Exception {
        RegistryAccess registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        CountDownLatch encodeStarted = new CountDownLatch(1);
        CountDownLatch releaseEncode = new CountDownLatch(1);
        AtomicBoolean encodeFinished = new AtomicBoolean(false);

        AsyncSaveWriter writer = newWriter(region);

        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            encodeStarted.countDown();
            try {
                releaseEncode.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CompoundTag tag = codec.encode(SyntheticChunks.full(registries, true), registries, false);
            encodeFinished.set(true);
            return tag;
        }, ChunkMerge::merge);

        assertTrue(encodeStarted.await(30, TimeUnit.SECONDS), "the writer picked the chunk up");

        AtomicBoolean pauseReturned = new AtomicBoolean(false);
        Thread client = new Thread(() -> {
            writer.pauseEncoding();
            pauseReturned.set(true);
        }, "test-client-thread");
        client.start();

        // Well inside the handshake's own abandon timeout, so this observes the wait rather than racing it.
        for (int i = 0; i < 10 && !pauseReturned.get(); i++) {
            Thread.sleep(5);
        }
        assertFalse(pauseReturned.get(), "pause must not return while an encode is still in flight");

        releaseEncode.countDown();
        client.join(30_000);

        assertTrue(pauseReturned.get(), "pause returns once the in-flight encode finishes");
        assertTrue(encodeFinished.get(), "the in-flight encode was allowed to complete rather than being abandoned");

        writer.resumeEncoding();
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the drain succeeded");
    }

    @Test
    void reportsHowLongTheHoldBlockedTheClientThread(@TempDir Path save) throws Exception {
        RegistryAccess registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        CountDownLatch encodeStarted = new CountDownLatch(1);
        CountDownLatch releaseEncode = new CountDownLatch(1);

        AsyncSaveWriter writer = newWriter(region);
        writer.submitChunk(Level.OVERWORLD, new ChunkPos(0, 0), () -> {
            encodeStarted.countDown();
            try {
                releaseEncode.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return codec.encode(SyntheticChunks.full(registries, true), registries, false);
        }, ChunkMerge::merge);
        assertTrue(encodeStarted.await(30, TimeUnit.SECONDS), "the writer picked the chunk up");

        try (LogCapture captured = LogCapture.attach(WRITER_LOGGER)) {
            CountDownLatch reachedTheHold = new CountDownLatch(1);
            Thread client = new Thread(() -> {
                reachedTheHold.countDown();
                writer.pauseEncoding();
            }, "test-client-thread");
            client.start();
            assertTrue(reachedTheHold.await(30, TimeUnit.SECONDS), "the client thread reached the hold");
            Thread.sleep(80);
            releaseEncode.countDown();
            client.join(30_000);

            int last = captured.count() - 1;
            assertTrue(last >= 0, "the hold said what it cost");
            assertEquals("INFO", captured.level(last),
                    "at INFO, because the field report this exists for carries no debug log");
            String line = captured.rendered(last);
            Matcher waited = Pattern.compile("blocked for (\\d+) ms").matcher(line);
            assertTrue(waited.find(), "the line names the wait in milliseconds: " + line);
            assertTrue(Long.parseLong(waited.group(1)) >= 50,
                    "the line reports the wait it actually took rather than zero: " + line);
        }

        writer.resumeEncoding();
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the drain succeeded");
    }

    @Test
    void runsNoLevelDataWriteWhileEncodingIsPaused(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean loaderStateAvailable = new AtomicBoolean(true);
        AtomicBoolean writtenDuringRebuild = new AtomicBoolean(false);
        CountDownLatch written = new CountDownLatch(1);

        AsyncSaveWriter writer = newWriter(region, () -> {
            if (!loaderStateAvailable.get()) {
                writtenDuringRebuild.set(true);
            }
            written.countDown();
        }, (chunksFailed, entityChunksFailed) -> {});

        writer.pauseEncoding();
        loaderStateAvailable.set(false);
        CompletableFuture<AsyncSaveWriter.SaveResult> result = writer.finish();

        // Well inside the writer's own self-release bound, so this observes the hold rather than racing it.
        assertFalse(written.await(200, TimeUnit.MILLISECONDS), "no level.dat write runs while the writer is held");

        loaderStateAvailable.set(true);
        writer.resumeEncoding();

        assertFalse(result.get(30, TimeUnit.SECONDS).failed(), "the save completed");
        assertEquals(0L, written.getCount(), "the level.dat write ran once resumed");
        assertFalse(writtenDuringRebuild.get(), "level.dat was not written while the loader state was unavailable");
    }

    @Test
    void pauseWaitsForTheLevelDataWriteAlreadyInFlight(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean writeFinished = new AtomicBoolean(false);

        AsyncSaveWriter writer = newWriter(region, () -> {
            writeStarted.countDown();
            releaseWrite.await(30, TimeUnit.SECONDS);
            writeFinished.set(true);
        }, (chunksFailed, entityChunksFailed) -> {});

        CompletableFuture<AsyncSaveWriter.SaveResult> result = writer.finish();
        assertTrue(writeStarted.await(30, TimeUnit.SECONDS), "the writer reached the level.dat write");

        AtomicBoolean pauseReturned = new AtomicBoolean(false);
        Thread client = new Thread(() -> {
            writer.pauseEncoding();
            pauseReturned.set(true);
        }, "test-client-thread");
        client.start();

        // Past the chunk bound and inside the finalize one, so a level.dat write left on the chunk bound fails here.
        for (int i = 0; i < 80 && !pauseReturned.get(); i++) {
            Thread.sleep(5);
        }
        assertFalse(pauseReturned.get(), "pause must not return while a level.dat write is still in flight");

        releaseWrite.countDown();
        client.join(30_000);

        assertTrue(pauseReturned.get(), "pause returns once the in-flight level.dat write finishes");
        assertTrue(writeFinished.get(), "the in-flight level.dat write was allowed to complete rather than abandoned");

        writer.resumeEncoding();
        assertFalse(result.get(30, TimeUnit.SECONDS).failed(), "the save completed");
    }

    @Test
    void lapsedWaitAroundTheLevelDataWriteCountsAsLost(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicInteger tallyReceived = new AtomicInteger(-1);

        AsyncSaveWriter writer = newWriter(region, () -> {
            writeStarted.countDown();
            releaseWrite.await(30, TimeUnit.SECONDS);
        }, (chunksFailed, entityChunksFailed) -> tallyReceived.set(chunksFailed));

        CompletableFuture<AsyncSaveWriter.SaveResult> result = writer.finish();
        assertTrue(writeStarted.await(30, TimeUnit.SECONDS), "the writer reached the level.dat write");

        Thread client = new Thread(writer::pauseEncoding, "test-client-thread");
        client.start();
        client.join(10_000);
        assertFalse(client.isAlive(), "pause gave up on the parked level.dat write once its bound lapsed");

        releaseWrite.countDown();
        writer.resumeEncoding();
        AsyncSaveWriter.SaveResult saved = result.get(30, TimeUnit.SECONDS);

        assertFalse(saved.failed(), "the save completed");
        assertEquals(1, saved.chunksFailed(), "the lapse is counted, so the finish reports partial");
        assertEquals(saved.chunksFailed(), tallyReceived.get(),
                "the finalizer received the counted lapse, so the record stamps what the finish reports");
    }

    @Test
    void pauseDoesNotWaitForTheFinalizeInFlight(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        CountDownLatch finalizeStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalize = new CountDownLatch(1);

        AsyncSaveWriter writer = newWriter(region, (chunksFailed, entityChunksFailed) -> {
            finalizeStarted.countDown();
            releaseFinalize.await(30, TimeUnit.SECONDS);
        });

        CompletableFuture<AsyncSaveWriter.SaveResult> result = writer.finish();
        assertTrue(finalizeStarted.await(30, TimeUnit.SECONDS), "the writer reached the finalize");

        Thread client = new Thread(writer::pauseEncoding, "test-client-thread");
        client.start();
        client.join(500);
        assertFalse(client.isAlive(), "pause returns at once while the finalize runs, since it holds no permit");

        releaseFinalize.countDown();
        writer.resumeEncoding();
        AsyncSaveWriter.SaveResult saved = result.get(30, TimeUnit.SECONDS);

        assertFalse(saved.failed(), "the save completed");
        assertEquals(0, saved.chunksFailed(), "nothing lapsed, so the finish reports clean");
    }

    @Test
    void aThrowingLevelDataWriteFailsTheSaveAndReleasesThePermit(@TempDir Path save) throws Exception {
        Path region = Files.createDirectories(save.resolve("region"));
        AtomicBoolean finalized = new AtomicBoolean(false);

        AsyncSaveWriter writer = newWriter(region, () -> {
            throw new IOException("level.dat could not be written");
        }, (chunksFailed, entityChunksFailed) -> finalized.set(true));

        AsyncSaveWriter.SaveResult saved = writer.finish().get(30, TimeUnit.SECONDS);
        assertTrue(saved.failed(), "a level.dat write that throws fails the save");
        assertFalse(finalized.get(), "and the finalizer never ran");

        Thread client = new Thread(writer::pauseEncoding, "test-client-thread");
        client.start();
        client.join(500);
        assertFalse(client.isAlive(), "the permit was released on the throw, so a pause returns at once");
    }

    private AsyncSaveWriter newWriter(Path region) {
        return newWriter(region, (chunksFailed, entityChunksFailed) -> {});
    }

    private AsyncSaveWriter newWriter(Path region, AsyncSaveWriter.Finalizer finalizer) {
        return newWriter(region, () -> {}, finalizer);
    }

    private AsyncSaveWriter newWriter(Path region, AsyncSaveWriter.LevelDataWrite levelDataWrite,
            AsyncSaveWriter.Finalizer finalizer) {
        return new AsyncSaveWriter(
                dimension -> storage(region, "chunk"),
                dimension -> {
                    throw new AssertionError("no entities were submitted");
                },
                () -> {}, levelDataWrite, finalizer, () -> null, () -> {}, new SaveProgress());
    }
}
