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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ChunkCodecImpl;
import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

class OrphanedContainerSweepTest {
    private final ChunkCodec codec = new ChunkCodecImpl();

    private static IOWorker storage(Path region) {
        return new TestRegionStorage(region, false, "chunk");
    }

    private static final class TestRegionStorage extends IOWorker {
        private TestRegionStorage(Path directory, boolean sync, String name) {
            super(directory.toFile(), sync, name);
        }
    }

    private static AsyncSaveWriter regionWriter(Path region) {
        return new AsyncSaveWriter(
                dimension -> storage(region),
                dimension -> {
                    throw new AssertionError("no entities were submitted, so the entities storage must not open");
                },
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
    }

    private static Map<BlockPos, CompoundTag> holder(BlockPos pos, CompoundTag tag) {
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(pos, tag);
        return holders;
    }

    private static @Nullable CompoundTag blockEntityAt(CompoundTag chunkTag, int x, int y, int z) {
        ListTag list = chunkTag.getCompound("Level").getList("TileEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag blockEntity = list.getCompound(i);
            if (blockEntity.getInt("x") == x && blockEntity.getInt("y") == y
                    && blockEntity.getInt("z") == z) {
                return blockEntity;
            }
        }
        return null;
    }

    private NonNullList<ItemStack> itemsOnDisk(Path region, ChunkPos chunk, int x, int y, int z,
            RegistryAccess registries)
            throws IOException {
        try (IOWorker in = storage(region)) {
            CompoundTag back = Optional.ofNullable(in.load(chunk))
                    .orElseThrow(() -> new AssertionError("chunk missing on disk"));
            CompoundTag blockEntity = blockEntityAt(back, x, y, z);
            assertNotNull(blockEntity, "block entity present on disk at " + x + "," + y + "," + z);
            NonNullList<ItemStack> decoded = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(blockEntity, decoded);
            return decoded;
        }
    }

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
        RegistryAccess registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos chunk = new ChunkPos(0, 0);
        BlockPos chestPos = new BlockPos(2, 64, 2);

        // The client chunk packet never carries a chest's contents, so a not-yet-opened chest is on disk structurally
        // present but empty.
        flushEmptyChunk(region, chunk, registries, List.of(blockEntity("minecraft:chest", 2, 64, 2)));
        assertTrue(itemsOnDisk(region, chunk, 2, 64, 2, registries).get(0).isEmpty(),
                "the flushed chunk's chest starts empty, before the container is opened");

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
        RegistryAccess registries = TestRegistries.frozen();
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

        try (IOWorker in = storage(region)) {
            CompoundTag back = Optional.ofNullable(in.load(chunk))
                    .orElseThrow(() -> new AssertionError("chunk missing on disk"));
            CompoundTag lectern = blockEntityAt(back, 3, 64, 3);
            assertNotNull(lectern, "the lectern block entity is on disk");
            assertEquals("minecraft:writable_book", lectern.getCompound("Book").getString("id"),
                    "the orphaned lectern book reached the on-disk lectern with its item identity");
            assertEquals(7, (lectern.contains("Page") ? lectern.getInt("Page") : -1), "and its reading page");
        }
    }

    @Test
    void bothOrphanedDoubleChestHalvesInSeparateChunksAreRecovered(@TempDir Path save) throws Exception {
        RegistryAccess registries = TestRegistries.frozen();
        Path region = Files.createDirectories(save.resolve("region"));
        ContainerSink sink = new ContainerSinkImpl();
        ChunkPos rightChunk = new ChunkPos(0, 0);
        ChunkPos leftChunk = new ChunkPos(1, 0);
        BlockPos rightHalf = new BlockPos(15, 64, 4);
        BlockPos leftHalf = new BlockPos(16, 64, 4);

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
        RegistryAccess registries = TestRegistries.frozen();
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

        try (IOWorker in = storage(region)) {
            assertTrue(Optional.ofNullable(in.load(missing)).isEmpty(),
                    "no partial chunk is written when there is no prior");
        }
    }

    @Test
    void aSameWriterRewriteReadsItsOwnUnsyncedWriteAndCountsTheMerge(@TempDir Path save) throws Exception {
        RegistryAccess registries = TestRegistries.frozen();
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
        Set<BlockPos> containers = new LinkedHashSet<>(List.of(
                new BlockPos(2, 64, 2),
                new BlockPos(15, 70, 15),
                new BlockPos(16, 64, 3)));
        Set<BlockPos> lecterns = new LinkedHashSet<>(List.of(
                new BlockPos(1, 65, 1),
                new BlockPos(40, 64, 8)));

        Set<ChunkPos> chunks = ChunkFlushPlan.residualHolderChunks(containers, lecterns);

        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0)), chunks,
                "both stashes contribute and holders sharing a chunk collapse to one target");
    }
}
