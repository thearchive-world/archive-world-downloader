// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.NonNullList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
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
 * <p>These headless tests drive the real writer against a real {@link WdlRegionStorage}, the seam the fix lives at: a
 * chunk is flushed to disk carrying an empty container (the flushed-empty orphaned state a backtrack-and-open lands
 * in), then the orphan rewrite folds the captured contents onto the on-disk block entity. The session wiring that
 * identifies the orphaned holders and routes them here ({@code LiveCaptureSession.flushBuffer}'s whole-buffer drain) is
 * not exercised headless, as with the rest of the MC-coupled session.
 */
class OrphanedContainerSweepTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    private static WdlRegionStorage storage(Path region) {
        return new WdlRegionStorage(region.toFile());
    }

    private static AsyncSaveWriter regionWriter(Path region) {
        return new AsyncSaveWriter(
                dimension -> storage(region),
                () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, new SaveProgress());
    }

    /** A mutable single-entry holder map, matching what the session's per-chunk drain hands the fold. */
    private static Map<BlockPos, NBTTagCompound> holder(BlockPos pos, NBTTagCompound tag) {
        Map<BlockPos, NBTTagCompound> holders = new LinkedHashMap<>();
        holders.put(pos, tag);
        return holders;
    }

    private static @Nullable NBTTagCompound blockEntityAt(NBTTagCompound chunkTag, int x, int y, int z) {
        NBTTagList list = chunkTag.getCompoundTag("Level").getTagList("TileEntities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound blockEntity = list.getCompoundTagAt(i);
            if (blockEntity.getInteger("x") == x && blockEntity.getInteger("y") == y
                    && blockEntity.getInteger("z") == z) {
                return blockEntity;
            }
        }
        return null;
    }

    /** Read chunk {@code chunk} back from disk and decode the 27-slot container contents of the block entity there. */
    private NonNullList<ItemStack> itemsOnDisk(Path region, ChunkPos chunk, int x, int y, int z)
            throws IOException {
        try (WdlRegionStorage in = storage(region)) {
            NBTTagCompound back = Optional.ofNullable(in.read(chunk))
                    .orElseThrow(() -> new AssertionError("chunk missing on disk"));
            NBTTagCompound blockEntity = blockEntityAt(back, x, y, z);
            assertNotNull(blockEntity, "block entity present on disk at " + x + "," + y + "," + z);
            NonNullList<ItemStack> decoded = NonNullList.withSize(27, ItemStack.EMPTY);
            ItemStackHelper.loadAllItems(blockEntity, decoded);
            return decoded;
        }
    }

    /** Flush a chunk to disk carrying the given (empty, client-form) block entities: the pre-open orphaned state. */
    private void flushEmptyChunk(Path region, ChunkPos chunk,
            List<NBTTagCompound> blockEntities)
            throws Exception {
        AsyncSaveWriter flush = regionWriter(region);
        flush.submitChunk(DimensionType.OVERWORLD, chunk,
                () -> codec.encode(SyntheticChunks.fullWithBlockEntities(true, blockEntities),
                        false),
                ChunkMerge::merge);
        assertFalse(flush.finish().get(30, TimeUnit.SECONDS).failed(), "the empty-container chunk flushed to disk");
    }

    @Test
    void orphanedContainerContentsAreFoldedOntoTheFlushedOnDiskChest(@TempDir Path save) throws Exception {
        TestRegistries.bootstrap();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(2, 64, 2);

        // The chunk is flushed carrying an empty chest, then leaves the keep-hot buffer: the client chunk packet
        // never carries container contents, so a not-yet-opened chest is on disk structurally present but empty.
        flushEmptyChunk(region, chunk, ImmutableList.of(blockEntity("minecraft:chest", 2, 64, 2)));
        assertTrue(itemsOnDisk(region, chunk, 2, 64, 2).get(0).isEmpty(),
                "the flushed chunk's chest starts empty, before the container is opened");

        // The container is opened after its chunk was flushed, so the captured Items holder is orphaned; the fix
        // folds it onto the on-disk chest through the writer-thread rewrite.
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, NBTTagCompound> holders = new LinkedHashMap<>();
        holders.put(chestPos, sink.captureItems(items));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(DimensionType.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, chunk, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        NonNullList<ItemStack> onDisk = itemsOnDisk(region, chunk, 2, 64, 2);
        assertEquals(Items.DIAMOND, onDisk.get(0).getItem(), "the orphaned contents reached the on-disk chest");
        assertEquals(5, onDisk.get(0).getCount(), "with the right count");
        assertTrue(onDisk.get(1).isEmpty(), "the fold set only the captured slot, leaving the rest of the chest empty");
    }

    @Test
    void orphanedLecternBookIsFoldedOntoTheFlushedOnDiskLectern(@TempDir Path save) throws Exception {
        TestRegistries.bootstrap();
        Path region = Files.createDirectories(save.resolve("region"));
        LecternSink sink = new LecternSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        BlockPos lecternPos = new BlockPos(3, 64, 3);

        // No vanilla lectern exists at this band, so a fieldless ender chest stands in as the carrier the orphan
        // sweep's LecternSink book fold writes "Book"/"Page" onto; the sweep-fold wiring is what is under test.
        flushEmptyChunk(region, chunk, ImmutableList.of(blockEntity("minecraft:ender_chest", 3, 64, 3)));

        Map<BlockPos, NBTTagCompound> holders = new LinkedHashMap<>();
        holders.put(lecternPos, sink.captureBook(new ItemStack(Items.WRITABLE_BOOK), 7));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(DimensionType.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeLecternChunkStash(sink, onDisk, chunk, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        try (WdlRegionStorage in = storage(region)) {
            NBTTagCompound back = Optional.ofNullable(in.read(chunk))
                    .orElseThrow(() -> new AssertionError("chunk missing on disk"));
            NBTTagCompound lectern = blockEntityAt(back, 3, 64, 3);
            assertNotNull(lectern, "the lectern block entity is on disk");
            assertEquals("minecraft:writable_book", lectern.getCompoundTag("Book").getString("id"),
                    "the orphaned lectern book reached the on-disk lectern with its item identity");
            assertEquals(7, (lectern.hasKey("Page") ? lectern.getInteger("Page") : -1), "and its reading page");
        }
    }

    @Test
    void bothOrphanedDoubleChestHalvesInSeparateChunksAreRecovered(@TempDir Path save) throws Exception {
        // A double chest straddling a chunk boundary keys each half by its own pos into its own chunk (the split
        // anomaly: the server sends both halves together, so one half saved and one lost is a mod-side loss). When
        // both halves' chunks are orphaned, the sweep folds each half onto its own on-disk chest.
        TestRegistries.bootstrap();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos rightChunk = new ChunkPos(0, 0);
        ChunkPos leftChunk = new ChunkPos(1, 0);
        BlockPos rightHalf = new BlockPos(15, 64, 4); // last column of chunk (0,0)
        BlockPos leftHalf = new BlockPos(16, 64, 4);  // first column of chunk (1,0), the connected partner

        flushEmptyChunk(region, rightChunk, ImmutableList.of(blockEntity("minecraft:chest", 15, 64, 4)));
        flushEmptyChunk(region, leftChunk, ImmutableList.of(blockEntity("minecraft:chest", 16, 64, 4)));

        NonNullList<ItemStack> rightItems = NonNullList.withSize(27, ItemStack.EMPTY);
        rightItems.set(0, new ItemStack(Items.EMERALD, 3));
        NonNullList<ItemStack> leftItems = NonNullList.withSize(27, ItemStack.EMPTY);
        leftItems.set(0, new ItemStack(Items.GOLD_INGOT, 9));
        Map<BlockPos, NBTTagCompound> rightHolder = holder(rightHalf, sink.captureItems(rightItems));
        Map<BlockPos, NBTTagCompound> leftHolder = holder(leftHalf, sink.captureItems(leftItems));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(DimensionType.OVERWORLD, rightChunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, rightChunk, rightHolder).merged());
        sweep.submitChunkRewrite(DimensionType.OVERWORLD, leftChunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, leftChunk, leftHolder).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(), "the orphan sweep completed");

        assertEquals(Items.EMERALD, itemsOnDisk(region, rightChunk, 15, 64, 4).get(0).getItem(),
                "the right half's orphaned contents reached its on-disk chest");
        assertEquals(Items.GOLD_INGOT, itemsOnDisk(region, leftChunk, 16, 64, 4).get(0).getItem(),
                "the left half's orphaned contents reached its on-disk chest (no split loss)");
    }

    @Test
    void anOrphanRewriteWithNoOnDiskPriorWritesNothingAndDoesNotFail(@TempDir Path save) throws Exception {
        // The chunk-not-on-disk edge: a chunk whose original write failed or was skipped has no prior to fold
        // into. The rewrite finds nothing, logs the loss, and completes without aborting the save or writing a
        // partial chunk (in practice the writer's FIFO order guarantees an orphaned chunk was written first).
        TestRegistries.bootstrap();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos missing = new ChunkPos(5, 5);
        Map<BlockPos, NBTTagCompound> holders = holder(new BlockPos(82, 64, 82),
                sink.captureItems(NonNullList.withSize(27, ItemStack.EMPTY)));

        AsyncSaveWriter sweep = regionWriter(region);
        sweep.submitChunkRewrite(DimensionType.OVERWORLD, missing,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, missing, holders).merged());
        assertFalse(sweep.finish().get(30, TimeUnit.SECONDS).failed(),
                "a rewrite for a chunk with no on-disk prior does not fail the save");

        try (WdlRegionStorage in = storage(region)) {
            assertTrue(!Optional.ofNullable(in.read(missing)).isPresent(),
                    "no partial chunk is written when there is no prior");
        }
    }

    @Test
    void aSameWriterRewriteReadsItsOwnUnsyncedWriteAndCountsTheMerge(@TempDir Path save) throws Exception {
        // Production reuses one writer per session: the orphaned chunk's original submitChunk and the later
        // submitChunkRewrite hit the same still-open storage, so the rewrite's read must return the earlier write
        // back through that same synchronous region storage, not a reopened file.
        // Drive that exact single-writer timing, and assert the merge is counted (the loss it fixes read zero on
        // every counter).
        TestRegistries.bootstrap();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 5));
        Map<BlockPos, NBTTagCompound> holders = holder(new BlockPos(2, 64, 2), sink.captureItems(items));

        AsyncSaveWriter writer = regionWriter(region);
        writer.submitChunk(DimensionType.OVERWORLD, chunk, () -> codec.encode(
                SyntheticChunks.fullWithBlockEntities(true,
                        ImmutableList.of(blockEntity("minecraft:chest", 2, 64, 2))),
                false), ChunkMerge::merge);
        writer.submitChunkRewrite(DimensionType.OVERWORLD, chunk,
                onDisk -> ContainerMerge.mergeChunkStash(sink, onDisk, chunk, holders).merged());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the single-writer write-then-rewrite completed");
        assertEquals(1, result.mergedContainers(), "the orphaned container merge is counted, not silently zero");
        assertEquals(Items.DIAMOND, itemsOnDisk(region, chunk, 2, 64, 2).get(0).getItem(),
                "the rewrite folded onto the same-session write it read back from the writer's pending storage");
    }

    @Test
    void residualHolderChunksGroupsBothStashesByChunkListingEachOnce() {
        // The orphan-selection reduction the session sweep runs over its two stashes: both stashes contribute, and
        // several holders sharing a chunk collapse to a single rewrite target.
        Set<BlockPos> containers = new LinkedHashSet<>(ImmutableList.of(
                new BlockPos(2, 64, 2),     // chunk (0,0)
                new BlockPos(15, 70, 15),   // chunk (0,0) too
                new BlockPos(16, 64, 3)));  // chunk (1,0)
        Set<BlockPos> lecterns = new LinkedHashSet<>(ImmutableList.of(
                new BlockPos(1, 65, 1),     // chunk (0,0)
                new BlockPos(40, 64, 8)));  // chunk (2,0)

        Set<ChunkPos> chunks = ChunkFlushPlan.residualHolderChunks(containers, lecterns);

        assertEquals(ImmutableSet.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0)), chunks,
                "both stashes contribute and holders sharing a chunk collapse to one target");
    }
}
