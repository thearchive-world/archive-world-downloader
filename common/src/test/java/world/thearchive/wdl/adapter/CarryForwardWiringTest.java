// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
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
 * Driven end to end through a real region file: the decision lives in which read-merge the flush hands the writer, and
 * a test that calls a merge itself passes whichever merge it chose, which is exactly what a mis-wired call site does
 * not change.
 */
class CarryForwardWiringTest {
    private final BlockPos chest = new BlockPos(6, 64, 11);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aPriorOnDiskIsCarriedForward(@TempDir Path temporary) throws Exception {
        ChunkPos pos = new ChunkPos(chest);
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));

        writePrior(paths, pos, chestHolding("minecraft:diamond"));
        captureChunk(session, pos, snapshotWithEmptyChest());

        AsyncSaveWriter writer = saveWriter(paths);
        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        assertEquals(ImmutableList.of("minecraft:diamond"), itemsOnDisk(paths, pos),
                "a re-walk through this world's own chunk carries its captured contents forward as before");
    }

    @Test
    void aReplacedPositionSuppressesOnceAndNotForever(@TempDir Path temporary) throws Exception {
        ChunkPos pos = new ChunkPos(chest);
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        placeBlockAt(session, chest);

        writePrior(paths, pos, chestHolding("minecraft:diamond"));
        captureChunk(session, pos, snapshotWithEmptyChest());
        AsyncSaveWriter first = saveWriter(paths);
        session.flushBuffer(first, true, 0, 0, 0);
        assertFalse(first.finish().get(30, TimeUnit.SECONDS).failed(), "the first flush landed");
        assertEquals(ImmutableList.of(), itemsOnDisk(paths, pos),
                "the block on disk was the one the placement replaced, so nothing of it carries");

        writePrior(paths, pos, chestHolding("minecraft:emerald"));
        captureChunk(session, pos, snapshotWithEmptyChest());
        AsyncSaveWriter second = saveWriter(paths);
        session.flushBuffer(second, true, 0, 0, 0);
        assertFalse(second.finish().get(30, TimeUnit.SECONDS).failed(), "the second flush landed");
        assertEquals(ImmutableList.of("minecraft:emerald"), itemsOnDisk(paths, pos),
                "and the visit after it carries forward what this download archived for the new block");
    }

    /**
     * The merge cannot tell this case from an ordinary revisit, because both fresh sides carry the same empty
     * {@code "Items"}; only the positions the flush derives from its own drained holders separate them, so a flush that
     * stopped deriving them, or handed over an empty list, would restore items the player watched leave and no other
     * case here would move.
     */
    @Test
    void aContainerThisFlushCapturedEmptyIsLeftEmptyOnDisk(@TempDir Path temporary) throws Exception {
        ChunkPos pos = new ChunkPos(chest);
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));

        writePrior(paths, pos, chestHolding("minecraft:diamond"));
        captureChunk(session, pos, snapshotWithEmptyChest());
        stashContainer(session, chest, BlockEntityFixtures.emptyContainerHolder(27, "minecraft:chest"));

        AsyncSaveWriter writer = saveWriter(paths);
        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        assertEquals(ImmutableList.of(), itemsOnDisk(paths, pos),
                "the open saw an empty chest, so the flush must leave it empty rather than restore what the "
                        + "earlier write archived there");
    }

    @SuppressWarnings("unchecked")
    private static void stashContainer(LiveCaptureSession session, BlockPos pos, CompoundTag holder)
            throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("containerStash");
        field.setAccessible(true);
        ((Map<BlockPos, StashHolder>) field.get(session)).put(pos, StashHolder.of(holder));
    }

    /**
     * Land a placement in {@code pos} through the recognizer the loader hook calls, so the session's own callback is
     * what marks the cell. Seeding the set directly would prove the flush consumes it and leave the line that fills it
     * unpinned.
     */
    private static void placeBlockAt(LiveCaptureSession session, BlockPos pos) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("interactionCapture");
        field.setAccessible(true);
        InteractionCapture capture = (InteractionCapture) field.get(session);
        assertNotNull(capture, "the fixture must publish a recognizer, or the placement reaches nothing");
        capture.recordPlaceAt(pos, new ItemStack(Items.CHEST));
    }

    @SuppressWarnings("unchecked")
    private static void stashLectern(LiveCaptureSession session, BlockPos pos, CompoundTag holder)
            throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("lecternStash");
        field.setAccessible(true);
        ((Map<BlockPos, StashHolder>) field.get(session)).put(pos, StashHolder.of(holder));
    }

    /**
     * A lectern captured at a cell and then built over keeps its book keyed to that position, and the merge gate
     * compares block-entity types, so a lectern placed there would inherit the book of the one it replaced.
     */
    @Test
    void aPlacementDropsTheLecternCapturedInThatCell(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        stashLectern(session, chest, new CompoundTag());

        placeBlockAt(session, chest);

        assertEquals(0, lecternStashSize(session),
                "the lectern the placement built over no longer describes anything at that cell");
    }

    @SuppressWarnings("unchecked")
    private static int lecternStashSize(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("lecternStash");
        field.setAccessible(true);
        return ((Map<BlockPos, StashHolder>) field.get(session)).size();
    }

    /** A captured chunk holding one chest exactly as the client saves it, which is with no contents. */
    private ChunkSnapshotSource snapshotWithEmptyChest() {
        return SyntheticChunks.withBlockEntityAt(chest, Blocks.CHEST.defaultBlockState(),
                blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ()));
    }

    private CompoundTag chestHolding(String... itemIds) {
        CompoundTag tag = blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ());
        tag.put("Items", ItemFixtures.items(itemIds));
        return tag;
    }

    private static void writePrior(WorldPaths paths, ChunkPos pos, CompoundTag blockEntity) throws Exception {
        CompoundTag chunk = BlockEntityFixtures.chunkTagWith(blockEntity);
        try (IOWorker storage = regionStorage(paths)) {
            storage.store(pos, chunk).join();
            storage.synchronize().join();
        }
    }

    private List<String> itemsOnDisk(WorldPaths paths, ChunkPos pos) throws Exception {
        try (IOWorker storage = regionStorage(paths)) {
            CompoundTag chunk = Optional.ofNullable(storage.load(pos))
                    .orElseThrow(() -> new AssertionError("chunk not on disk"));
            CompoundTag written = findByPos(chunk, chest.getX(), chest.getY(), chest.getZ());
            List<String> ids = new ArrayList<>();
            if (written.get("Items") instanceof ListTag) {
                ListTag items = (ListTag) written.get("Items");
                for (int i = 0; i < items.size(); i++) {
                    ids.add(((CompoundTag) items.get(i)).getString("id"));
                }
            }
            return ids;
        }
    }

    private static IOWorker regionStorage(WorldPaths paths) {
        return paths.openRegionStorage(DimensionType.OVERWORLD);
    }

    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        WdlConfig config = WdlConfig.parse(properties);
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static WorldPaths paths(Path save) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        Files.createDirectories(paths.regionDirectory(DimensionType.OVERWORLD));
        return paths;
    }

    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                dimension -> regionStorage(paths),
                () -> {}, () -> {}, (chunksFailed, entityChunksFailed) -> {}, () -> null, new SaveProgress());
    }

    @SuppressWarnings("unchecked")
    private static void captureChunk(LiveCaptureSession session, ChunkPos pos, ChunkSnapshotSource snapshot)
            throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("captured");
        field.setAccessible(true);
        ((Map<ChunkPos, ChunkSnapshotSource>) field.get(session)).put(pos, snapshot);
    }
}
