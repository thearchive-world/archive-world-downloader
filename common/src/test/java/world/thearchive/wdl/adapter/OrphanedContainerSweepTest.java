// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The orphaned open-time container and lectern loss: a container or lectern opened in a chunk that had already left the
 * keep-hot buffer is stashed by block pos, but the per-chunk flush only ever drains the still-buffered chunks, and the
 * chunk is never re-buffered (the allCaptured skip), so its captured contents were silently dropped. The trigger is the
 * normal way a storage base is downloaded: backtracking through chunks already flown past. The fix folds each such
 * residual holder into its on-disk chunk through a writer-thread read-modify-write
 * ({@link AsyncSaveWriter#submitChunkRewrite} over {@link RegionChunkWriter#rewriteExisting}), reusing the
 * {@link ContainerMerge} fold.
 *
 * <p>These headless tests drive the real writer against a real {@link SimpleRegionStorage}, the seam the fix lives at:
 * a chunk is flushed to disk carrying an empty container (the flushed-empty orphaned state a backtrack-and-open lands
 * in), then the orphan rewrite folds the captured contents onto the on-disk block entity. The session wiring that
 * identifies the orphaned holders and routes them here ({@code LiveCaptureSession.flushBuffer}'s whole-buffer drain) is
 * not exercised headless, as with the rest of the MC-coupled session.
 */
class OrphanedContainerSweepTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    private static SimpleRegionStorage storage(Path region) {
        return new SimpleRegionStorage(new RegionStorageInfo("wdl", Level.OVERWORLD, "chunk"), region,
                DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    private static AsyncSaveWriter regionWriter(Path region) {
        return new AsyncSaveWriter(
                dimension -> storage(region),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
    }

    /** A mutable single-entry holder map, matching what the session's per-chunk drain hands the fold. */
    private static Map<BlockPos, CompoundTag> holder(BlockPos pos, CompoundTag tag) {
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(pos, tag);
        return holders;
    }

    private static @Nullable CompoundTag blockEntityAt(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getListOrEmpty("block_entities");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag blockEntity = list.getCompoundOrEmpty(i);
            if (blockEntity.getIntOr("x", 0) == x && blockEntity.getIntOr("y", 0) == y
                    && blockEntity.getIntOr("z", 0) == z) {
                return blockEntity;
            }
        }
        return null;
    }

    /** Read chunk {@code chunk} back from disk and decode the 27-slot container contents of the block entity there. */
    private NonNullList<ItemStack> itemsOnDisk(Path region, ChunkPos chunk, int x, int y, int z,
            RegistryAccess registries)
            throws IOException {
        try (SimpleRegionStorage in = storage(region)) {
            CompoundTag back = in.read(chunk).join().orElseThrow(() -> new AssertionError("chunk missing on disk"));
            CompoundTag blockEntity = blockEntityAt(back, x, y, z);
            assertNotNull(blockEntity, "block entity present on disk at " + x + "," + y + "," + z);
            NonNullList<ItemStack> decoded = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, blockEntity),
                    decoded);
            return decoded;
        }
    }

    /** Flush a chunk to disk carrying the given (empty, client-form) block entities: the pre-open orphaned state. */
    private void flushEmptyChunk(Path region, ChunkPos chunk, RegistryAccess registries,
            List<CompoundTag> blockEntities)
            throws Exception {
        AsyncSaveWriter flush = regionWriter(region);
        flush.submitChunk(Level.OVERWORLD, chunk,
                () -> codec.encode(SyntheticChunks.fullWithBlockEntities(registries, true, blockEntities), registries,
                        false),
                ChunkMerge::merge);
        assertFalse(flush.finish().get(30, TimeUnit.SECONDS).failed(), "the empty-container chunk flushed to disk");
    }

    @Test
    void orphanedContainerContentsAreFoldedOntoTheFlushedOnDiskChest(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(2, 64, 2);

        // The chunk is flushed carrying an empty chest, then leaves the keep-hot buffer: the client chunk packet
        // never carries container contents, so a not-yet-opened chest is on disk structurally present but empty.
        flushEmptyChunk(region, chunk, registries, List.of(blockEntity("minecraft:chest", 2, 64, 2)));
        assertTrue(itemsOnDisk(region, chunk, 2, 64, 2, registries).get(0).isEmpty(),
                "the flushed chunk's chest starts empty, before the container is opened");

        // The container is opened after its chunk was flushed, so the captured Items holder is orphaned; the fix
        // folds it onto the on-disk chest through the writer-thread rewrite.
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(chestPos, sink.captureItems(items, registries));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(Level.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, chunk, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        NonNullList<ItemStack> onDisk = itemsOnDisk(region, chunk, 2, 64, 2, registries);
        assertEquals(Items.DIAMOND, onDisk.get(0).getItem(), "the orphaned contents reached the on-disk chest");
        assertEquals(5, onDisk.get(0).getCount(), "with the right count");
        assertTrue(onDisk.get(1).isEmpty(), "the fold set only the captured slot, leaving the rest of the chest empty");
    }

    @Test
    void orphanedLecternBookIsFoldedOntoTheFlushedOnDiskLectern(@TempDir Path save) throws Exception {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        LecternSink sink = new LecternSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        BlockPos lecternPos = new BlockPos(3, 64, 3);

        flushEmptyChunk(region, chunk, registries, List.of(blockEntity("minecraft:lectern", 3, 64, 3)));

        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(lecternPos, sink.captureBook(new ItemStack(Items.WRITABLE_BOOK), 7, registries));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(Level.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeLecternChunkStash(sink, onDisk, chunk, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        try (SimpleRegionStorage in = storage(region)) {
            CompoundTag back = in.read(chunk).join().orElseThrow(() -> new AssertionError("chunk missing on disk"));
            CompoundTag lectern = blockEntityAt(back, 3, 64, 3);
            assertNotNull(lectern, "the lectern block entity is on disk");
            assertEquals("minecraft:writable_book", lectern.getCompoundOrEmpty("Book").getStringOr("id", ""),
                    "the orphaned lectern book reached the on-disk lectern with its item identity");
            assertEquals(7, lectern.getIntOr("Page", -1), "and its reading page");
        }
    }

    @Test
    void bothOrphanedDoubleChestHalvesInSeparateChunksAreRecovered(@TempDir Path save) throws Exception {
        // A double chest straddling a chunk boundary keys each half by its own pos into its own chunk (the split
        // anomaly: the server sends both halves together, so one half saved and one lost is a mod-side loss). When
        // both halves' chunks are orphaned, the sweep folds each half onto its own on-disk chest.
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos rightChunk = new ChunkPos(0, 0);
        ChunkPos leftChunk = new ChunkPos(1, 0);
        BlockPos rightHalf = new BlockPos(15, 64, 4); // last column of chunk (0,0)
        BlockPos leftHalf = new BlockPos(16, 64, 4);  // first column of chunk (1,0), the connected partner

        flushEmptyChunk(region, rightChunk, registries, List.of(blockEntity("minecraft:chest", 15, 64, 4)));
        flushEmptyChunk(region, leftChunk, registries, List.of(blockEntity("minecraft:chest", 16, 64, 4)));

        NonNullList<ItemStack> rightItems = NonNullList.withSize(27, ItemStack.EMPTY);
        rightItems.set(0, new ItemStack(Items.EMERALD, 3));
        NonNullList<ItemStack> leftItems = NonNullList.withSize(27, ItemStack.EMPTY);
        leftItems.set(0, new ItemStack(Items.GOLD_INGOT, 9));
        Map<BlockPos, CompoundTag> rightHolder = holder(rightHalf, sink.captureItems(rightItems, registries));
        Map<BlockPos, CompoundTag> leftHolder = holder(leftHalf, sink.captureItems(leftItems, registries));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(Level.OVERWORLD, rightChunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, rightChunk, rightHolder).merged());
        sweep.submitChunkRewrite(Level.OVERWORLD, leftChunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, leftChunk, leftHolder).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        assertEquals(Items.EMERALD, itemsOnDisk(region, rightChunk, 15, 64, 4, registries).get(0).getItem(),
                "the right half's orphaned contents reached its on-disk chest");
        assertEquals(Items.GOLD_INGOT, itemsOnDisk(region, leftChunk, 16, 64, 4, registries).get(0).getItem(),
                "the left half's orphaned contents reached its on-disk chest (no split loss)");
    }

    @Test
    void anOrphanRewriteWithNoOnDiskPriorWritesNothingAndDoesNotFail(@TempDir Path save) throws Exception {
        // The chunk-not-on-disk edge: a chunk whose original write failed or was skipped has no prior to fold
        // into. The rewrite finds nothing, logs the loss, and completes without aborting the save or writing a
        // partial chunk (in practice the writer's FIFO order guarantees an orphaned chunk was written first).
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos missing = new ChunkPos(5, 5);
        Map<BlockPos, CompoundTag> holders = holder(new BlockPos(82, 64, 82),
                sink.captureItems(NonNullList.withSize(27, ItemStack.EMPTY), registries));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(Level.OVERWORLD, missing,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, missing, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(),
                "a rewrite for a chunk with no on-disk prior does not fail the save");

        try (SimpleRegionStorage in = storage(region)) {
            assertTrue(in.read(missing).join().isEmpty(), "no partial chunk is written when there is no prior");
        }
    }

    @Test
    void aSameWriterRewriteReadsItsOwnUnsyncedWriteAndCountsTheMerge(@TempDir Path save) throws Exception {
        // Production reuses one writer per session: the orphaned chunk's original submitChunk and the later
        // submitChunkRewrite hit the same still-open storage with no synchronize between, so the rewrite's read
        // must return the earlier write from the region IOWorker's pending writes, not a synced-and-reopened file.
        // Drive that exact single-writer timing, and assert the merge is counted (the loss it fixes read zero on
        // every counter).
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, CompoundTag> holders = holder(new BlockPos(2, 64, 2), sink.captureItems(items, registries));

        AsyncSaveWriter writer = regionWriter(region);
        writer.submitChunk(Level.OVERWORLD, chunk, () -> codec.encode(
                SyntheticChunks.fullWithBlockEntities(registries, true,
                        List.of(blockEntity("minecraft:chest", 2, 64, 2))),
                registries, false), ChunkMerge::merge);
        writer.submitChunkRewrite(Level.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, chunk, holders).merged());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the single-writer write-then-rewrite completed");
        assertEquals(1, result.mergedContainers(), "the orphaned container merge is counted, not silently zero");
        assertEquals(Items.DIAMOND, itemsOnDisk(region, chunk, 2, 64, 2, registries).get(0).getItem(),
                "the rewrite folded onto the same-session write it read back from the writer's pending storage");
    }

    @Test
    void residualHolderChunksGroupsBothStashesByChunkListingEachOnce() {
        // The orphan-selection reduction the session sweep runs over its two stashes: both stashes contribute, and
        // several holders sharing a chunk collapse to a single rewrite target.
        Set<BlockPos> containers = new LinkedHashSet<>(List.of(
                new BlockPos(2, 64, 2),     // chunk (0,0)
                new BlockPos(15, 70, 15),   // chunk (0,0) too
                new BlockPos(16, 64, 3)));  // chunk (1,0)
        Set<BlockPos> lecterns = new LinkedHashSet<>(List.of(
                new BlockPos(1, 65, 1),     // chunk (0,0)
                new BlockPos(40, 64, 8)));  // chunk (2,0)

        Set<ChunkPos> chunks = ChunkFlushPlan.residualHolderChunks(containers, lecterns);

        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0)), chunks,
                "both stashes contribute and holders sharing a chunk collapse to one target");
    }
}
