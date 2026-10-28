// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderOf;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderReferencing;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.LogCapture;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The two remap paths are reached reflectively. The alternative is to pin the two voices only by their field identity,
 * which would not notice a site rewired to the other path's voice.
 */
class LiveCaptureSessionLossLogWiringTest {
    private static final String SESSION_LOGGER = LiveCaptureSession.class.getName();

    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots; a test that turns either on leaves a session published there, since only the finish path clears
     * it. The toggles are asserted rather than assumed: an unrecognized config key falls back to the default, which is
     * on for both, so a silent key rename would arm every session this fixture builds.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static AsyncSaveWriter mapOnlyWriter() {
        return new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("a map write must not open a region storage");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                new SaveProgress());
    }

    private static Path unwritableDataRoot(Path save) throws Exception {
        Files.createDirectories(save);
        Files.write(save.resolve("data"), "not a directory".getBytes(StandardCharsets.UTF_8));
        return save;
    }

    private static MapArchive stubArchive() {
        return new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {});
    }

    private static MapArchive throwingArchive() {
        return new MapArchive(MapManifest.empty(), id -> {
            throw new IllegalStateException("the map image could not be read");
        }, (archiveId, dataTag) -> {});
    }

    private static CompoundTag mapData() {
        CompoundTag data = new CompoundTag();
        data.putString("dimension", "minecraft:overworld");
        data.putByteArray("colors", new byte[16384]);
        return data;
    }

    private CompoundTag entityChunkDisplaying(UUID uuid, ItemStack stack) {
        CompoundTag entity = EntityFixtures.entity("minecraft:item_frame", uuid);
        entity.put("Item", itemTagOf(stack));
        return EntityFixtures.entityChunkTagWith(entity);
    }

    private CompoundTag itemTagOf(ItemStack stack) {
        return (CompoundTag) ((ListTag) holderOf(sink, stack).get("Items")).get(0);
    }

    @Test
    void aSecondStreamedMapLostTheSameWayAddsNoSecondStack(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path save = unwritableDataRoot(temporary.resolve("save"));
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            AsyncSaveWriter writer = session.bindWorldOpen(paths, stubArchive(),
                    LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);

            session.streamMapData(7, mapData());
            session.streamMapData(8, mapData());
            AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

            assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
            assertEquals(2, captured.count(), "every lost map keeps its own line, so none becomes invisible");
            assertEquals("INFO", captured.level(1), "a lost map is a soft failure and logs at INFO");
            assertTrue(captured.rendered(1).contains("map_8"), "the second line names the map it lost");
            assertNotNull(captured.thrown(0), "the first map lost this download carries the cause's stack");
            assertNull(captured.thrown(1),
                    "one voice spans the whole download, so a second map lost the same way adds no second stack");
        }
    }

    @Test
    void aHolderRemapLossLeavesTheEntityRemapLossItsOwnStack(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        MapArchive archive = throwingArchive();
        BlockPos holderPosition = new BlockPos(4, 64, -7);
        UUID framed = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                    LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);

            remapHolderItems(session, holderReferencing(sink, 5), holderPosition);
            remapEntityItems(session, entityChunkDisplaying(framed, filledMap(9)), archive);
            AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

            assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
            assertEquals(2, captured.count(), "each remap path names what it lost on its own line");
            assertNotNull(captured.thrown(0), "the holder path's first loss carries the cause's stack");
            assertNotNull(captured.thrown(1),
                    "the entity path keeps a voice of its own, so its first loss is not represented by the "
                            + "holder path's stack merely for failing the same way");
            assertTrue(captured.rendered(0).startsWith("map remap failed for holder " + holderPosition),
                    "the holder path's line is the first one, and it names the holder that lost its map");
            assertTrue(captured.rendered(1).startsWith("map remap failed for entity " + framed),
                    "the entity path's line is the second one, and it names the entity whose item lost its map");
        }
    }

    @Test
    void aSecondDownloadsFirstLossStillCarriesItsStack(@TempDir Path temporary) throws Exception {
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            loseOneItemThroughEachVoice(session(temporary), temporary.resolve("first"));
            assertEquals(3, captured.count(), "the first download loses one item through each of the three voices");

            loseOneItemThroughEachVoice(session(temporary), temporary.resolve("second"));

            assertEquals(6, captured.count(), "the second download loses one item through each voice as well");
            String[] voices = { "map write", "holder remap", "entity remap" };
            for (int line = 3; line < 6; line++) {
                assertNotNull(captured.thrown(line),
                        "the " + voices[line - 3] + " voice lasts one download, so the next download's first loss "
                                + "carries the cause's stack rather than spending the bound the prior one used");
            }
        }
    }

    private void loseOneItemThroughEachVoice(LiveCaptureSession session, Path save) throws Exception {
        MapArchive archive = throwingArchive();
        WorldPaths paths = new VersionAdapterImpl().worldPaths(unwritableDataRoot(save));
        AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);

        remapHolderItems(session, holderReferencing(sink, 5), new BlockPos(4, 64, -7));
        remapEntityItems(session, entityChunkDisplaying(
                UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402"), filledMap(9)), archive);
        session.streamMapData(7, mapData());

        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);
        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
    }

    @Test
    void aMapWithNoWriterToStreamToStaysAtInfo(@TempDir Path temporary) {
        LiveCaptureSession session = session(temporary);
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            session.streamMapData(7, mapData());

            assertEquals(1, captured.count(), "a map with nowhere to go still leaves a record of the loss");
            assertEquals("INFO", captured.level(0),
                    "a lost map is a per-item capture loss, and field reports read those at INFO");
            assertTrue(captured.rendered(0).contains("map_7"), "the line names the map that never reached the save");
        }
    }

    @Test
    void aMapImagedAfterTheFinishBatchClosedStaysAtInfo(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            AsyncSaveWriter writer = session.bindWorldOpen(paths, stubArchive(),
                    LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);
            closeTheFinishBatch(session);

            session.streamMapData(7, mapData());
            AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

            assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
            assertEquals(1, captured.count(), "a map imaged too late still leaves a record of the loss");
            assertEquals("INFO", captured.level(0),
                    "a lost map is a per-item capture loss, and field reports read those at INFO");
            assertTrue(captured.rendered(0).contains("map_7"), "the line names the map that never reached the save");
        }
    }

    @Test
    void anIdCountsFailureStaysAtWarn(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path save = unwritableDataRoot(temporary.resolve("save"));
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        MapArchive archive = stubArchive();
        archive.archiveIdFor(42); // an id referenced this session is what gives idCountsTag a value to write
        try (LogCapture captured = LogCapture.attach(SESSION_LOGGER)) {
            AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                    LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);

            session.writeIdCounts(paths);
            AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

            assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
            assertEquals(1, captured.count(), "the one shared file fails once, so it says so once");
            assertEquals("WARN", captured.level(0),
                    "the shared idcounts file is a once-per-download fault, not a per-item loss, so it outranks "
                            + "the loss lines rather than joining them");
            assertNotNull(captured.thrown(0), "a fault that can only happen once carries its stack every time");
        }
    }

    private static void closeTheFinishBatch(LiveCaptureSession session) {
        try {
            Field closed = LiveCaptureSession.class.getDeclaredField("finishBatchClosed");
            closed.setAccessible(true);
            closed.setBoolean(session, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not close the finish batch", e);
        }
    }

    private static void remapHolderItems(LiveCaptureSession session, CompoundTag holder, Object identifier) {
        drive(session, "scrubAndRemapItems", new Class<?>[] { CompoundTag.class, Object.class }, holder, identifier);
    }

    private static void remapEntityItems(LiveCaptureSession session, CompoundTag entityChunkTag,
            MapArchive archive) {
        drive(session, "remapEntityItems", new Class<?>[] { CompoundTag.class, MapArchive.class },
                entityChunkTag, archive);
    }

    private static void drive(LiveCaptureSession session, String name, Class<?>[] parameters,
            Object... arguments) {
        try {
            Method method = LiveCaptureSession.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            method.invoke(session, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not drive " + name, e);
        }
    }
}
