// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The wiring guard for the chiseled-bookshelf occupancy mask: the flush must read it and hand it to the writer-side
 * carry-forward for every flushed chunk, whatever the capture toggles say.
 *
 * <p>This exists because the obvious optimization is wrong in a way that reads as obviously right. The mask looks like
 * a record of what this session captured, so skipping it when container capture is off looks free. It is not: the mask
 * re-gates what an EARLIER session wrote, and an absent entry reads downstream as occupancy unknown, which carries
 * every on-disk slot back. Skipping it therefore writes a book into a slot the same chunk's own saved block-state
 * reports empty, which is invisible in the loaded world, unreachable by hand, and destroyed without a drop by the next
 * insert there. That skip was written, shipped past a green suite, and caught by review; nothing in the suite moved,
 * because every other bookshelf case drives the merge directly rather than through the flush.
 *
 * <p>Driven end to end through a real region file for that reason: the defect lives in whether the flush calls the
 * producer at all, so a test that calls the producer itself cannot see it.
 */
class BookshelfOccupancyFlushTest {
    private static RegistryAccess.Frozen registries;

    private final BlockPos shelf = new BlockPos(4, 64, 9);

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    @Test
    void aFlushReGatesPriorBookshelfSlotsEvenWithContainerCaptureOff(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = containersOffSession(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        ChunkPos pos = new ChunkPos(shelf);

        // A prior session wrote two books. Since then slot 0 was emptied, which the block-state records and
        // this session re-captures, while the on-disk Items still names it.
        writePrior(paths, pos, bookshelfBlockEntity(0, 1));
        captureChunk(session, pos, snapshotWithShelfOccupying(1));

        AsyncSaveWriter writer = saveWriter(paths);
        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        assertEquals(List.of(1), slotsOnDisk(paths, pos),
                "the emptied slot is re-gated away rather than carried back; capturing no containers this "
                        + "session is not a reason to stop re-gating what an earlier one wrote");
    }

    /** A captured chunk holding one chiseled bookshelf whose named slots read occupied. */
    private ChunkSnapshotSource snapshotWithShelfOccupying(int... occupied) {
        BlockState state = Blocks.CHISELED_BOOKSHELF.defaultBlockState();
        for (int slot : occupied) {
            state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot), true);
        }
        return SyntheticChunks.withBlockEntityAt(registries, shelf, state, freshShelfBlockEntity());
    }

    /**
     * The bookshelf block entity as the client's own save produces it, rather than the subset this test's assertions
     * happen to read. Vanilla writes an empty Items list and last_interacted_slot unconditionally and the chunk capture
     * adds keepPacked, so a fixture carrying only id and position is a shape production never emits, and a later change
     * that told an empty Items apart from an absent one would pass here and fail in the world.
     */
    private CompoundTag freshShelfBlockEntity() {
        return BlockEntityFixtures.savedBlockEntity(
                blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, shelf.getX(), shelf.getY(), shelf.getZ()));
    }

    private CompoundTag bookshelfBlockEntity(int... slots) {
        CompoundTag tag = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, shelf.getX(), shelf.getY(), shelf.getZ());
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        tag.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return tag;
    }

    private static void writePrior(WorldPaths paths, ChunkPos pos, CompoundTag blockEntity) throws Exception {
        CompoundTag chunk = BlockEntityFixtures.chunkTagWith(blockEntity);
        try (SimpleRegionStorage storage = regionStorage(paths)) {
            storage.write(pos, chunk).join();
            storage.synchronize(true).join();
        }
    }

    private List<Integer> slotsOnDisk(WorldPaths paths, ChunkPos pos) throws Exception {
        try (SimpleRegionStorage storage = regionStorage(paths)) {
            CompoundTag chunk = storage.read(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            CompoundTag written = findByPos(chunk, shelf.getX(), shelf.getY(), shelf.getZ());
            List<Integer> slots = new ArrayList<>();
            if (written.get("Items") instanceof ListTag items) {
                for (int i = 0; i < items.size(); i++) {
                    slots.add((int) ((CompoundTag) items.get(i)).getByteOr("Slot", (byte) -1));
                }
            }
            slots.sort(null);
            return slots;
        }
    }

    private static SimpleRegionStorage regionStorage(WorldPaths paths) {
        return new SimpleRegionStorage(paths.regionStorageInfo(Level.OVERWORLD),
                paths.regionDirectory(Level.OVERWORLD), DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    /**
     * A session with container capture OFF, which is the configuration under test: the interaction recognizer is
     * absent, so nothing this session does can capture a bookshelf, and the mask is needed anyway.
     */
    private static LiveCaptureSession containersOffSession(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureContainers(), "the whole subject is the containers-off arm, so it must be off");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(), new CoveredChunkIndex(),
                new SendRangeEstimator(), false, false, () -> {});
    }

    private static WorldPaths paths(Path save) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        Files.createDirectories(paths.regionDirectory(Level.OVERWORLD));
        Files.createDirectories(paths.entitiesDirectory(Level.OVERWORLD));
        return paths;
    }

    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                dimension -> regionStorage(paths),
                dimension -> new SimpleRegionStorage(paths.entitiesStorageInfo(dimension),
                        paths.entitiesDirectory(dimension), DataFixers.getDataFixer(), false,
                        DataFixTypes.ENTITY_CHUNK),
                () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, () -> {}, new SaveProgress());
    }

    @SuppressWarnings("unchecked")
    private static void captureChunk(LiveCaptureSession session, ChunkPos pos, ChunkSnapshotSource snapshot)
            throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("captured");
        field.setAccessible(true);
        ((Map<ChunkPos, ChunkSnapshotSource>) field.get(session)).put(pos, snapshot);
    }
}
