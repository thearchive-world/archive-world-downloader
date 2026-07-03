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
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The wiring guard for what a chunk flush lets its on-disk prior carry forward, driven end to end through a real region
 * file: the decision lives in which read-merge the flush hands the writer, and a test that calls a merge itself passes
 * whichever merge it chose, which is exactly what a mis-wired call site does not change.
 */
class CarryForwardWiringTest {
    private static RegistryAccess.Frozen registries;

    private final BlockPos chest = new BlockPos(6, 64, 11);

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /** An ordinary revisit must still keep what this download captured at that position. */
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
        assertEquals(List.of("minecraft:diamond"), itemsOnDisk(paths, pos),
                "a re-walk through this world's own chunk carries its captured contents forward as before");
    }

    /** A captured chunk holding one chest exactly as the client saves it, which is with no contents. */
    private ChunkSnapshotSource snapshotWithEmptyChest() {
        return SyntheticChunks.withBlockEntityAt(registries, chest, Blocks.CHEST.defaultBlockState(),
                blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ()));
    }

    private CompoundTag chestHolding(String... itemIds) {
        CompoundTag tag = blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ());
        tag.put("Items", ItemFixtures.items(itemIds));
        return tag;
    }

    private static void writePrior(WorldPaths paths, ChunkPos pos, CompoundTag blockEntity) throws Exception {
        CompoundTag chunk = BlockEntityFixtures.chunkTagWith(blockEntity);
        try (SimpleRegionStorage storage = regionStorage(paths)) {
            storage.write(pos, chunk).join();
            storage.synchronize(true).join();
        }
    }

    private List<String> itemsOnDisk(WorldPaths paths, ChunkPos pos) throws Exception {
        try (SimpleRegionStorage storage = regionStorage(paths)) {
            CompoundTag chunk = storage.read(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            CompoundTag written = findByPos(chunk, chest.getX(), chest.getY(), chest.getZ());
            List<String> ids = new ArrayList<>();
            if (written.get("Items") instanceof ListTag items) {
                for (int i = 0; i < items.size(); i++) {
                    ids.add(((CompoundTag) items.get(i)).getStringOr("id", ""));
                }
            }
            return ids;
        }
    }

    private static SimpleRegionStorage regionStorage(WorldPaths paths) {
        return new SimpleRegionStorage(paths.regionStorageInfo(Level.OVERWORLD),
                paths.regionDirectory(Level.OVERWORLD), DataFixers.getDataFixer(), false, DataFixTypes.CHUNK);
    }

    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        WdlConfig config = WdlConfig.parse(properties);
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), () -> {});
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
