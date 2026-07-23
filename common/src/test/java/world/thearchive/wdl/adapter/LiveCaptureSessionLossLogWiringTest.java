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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.LogCapture;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * How the session is wired to its per-loss log voices, which the voice's own test cannot see because it holds the voice
 * itself. Four joints. A streamed map write must speak through the voice the session holds for the whole download,
 * since a voice built per write attaches a stack per lost map, and a display wall's finish drain loses them five
 * figures at a time. The container-holder and the entity-item remap paths must hold voices of their own, since the
 * stack bound is per cause type per voice, so one shared voice lets a holder loss silence the stack of an unrelated
 * entity loss that happens to fail the same way. A voice must not outlive its download either, since the same bound
 * then spends the only stack of a cause on whichever download hit it first and leaves every download after it
 * stackless. And the three sites that still log directly rather than through a voice must keep the level their audience
 * reads them at: a lost map is a per-item soft loss at INFO, while the shared idcounts file is a once-per-download
 * fault at WARN.
 *
 * <p>The two remap paths are reached reflectively. They are private, and every production caller runs behind the client
 * singleton, so none of them runs headlessly: the flush pump reaches the drained-holder and entity-container paths, and
 * finish() reaches the ridden-mount fold directly, a sibling entry to the pump rather than a descendant of it. The
 * alternative is to pin the two voices only by their field identity, which would not notice a site rewired to the other
 * path's voice.
 */
class LiveCaptureSessionLossLogWiringTest {
    private static final String SESSION_LOGGER = LiveCaptureSession.class.getName();

    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen(); // vanilla statics, which a test reaching a map write needs first
    }

    /**
     * A session with no bound level, which is all the map paths need. Entity and container capture are off so the
     * constructor publishes no process-wide capture into the static activation slots; a test that turns either on
     * leaves a session published there for the rest of the suite JVM, since only finish() clears it. The toggles are
     * asserted rather than assumed: an unrecognized config key falls back to the default, which is on for both, so a
     * silent key rename would arm every session this fixture builds.
     */
    private static LiveCaptureSession session(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SendRangeEstimator(), false,
                () -> {});
    }

    /**
     * A writer whose region and entity storages are never opened, since a map write reaches disk through MapDataWriter
     * and never through a region storage. The openers throw rather than returning a stub, but the writer thread turns a
     * throw into a failed SaveResult, so a test that binds this must assert the result did not fail for the guard to
     * have any force.
     */
    private static AsyncSaveWriter mapOnlyWriter() {
        return new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("a map write must not open a region storage");
                },
                dimension -> {
                    throw new AssertionError("a map write must not open an entities storage");
                },
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                new SaveProgress());
    }

    /** A regular file where the {@code data/} directory belongs, so createDirectories throws for every write. */
    private static Path unwritableDataRoot(Path save) throws Exception {
        Files.createDirectories(save);
        Files.writeString(save.resolve("data"), "not a directory");
        return save;
    }

    /** An archive whose image resolver and data sink are both inert, so a test drives the session directly. */
    private static MapArchive stubArchive() {
        return new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {});
    }

    /** An archive that fails every id resolution the same way, the deterministic remap loss both paths absorb. */
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

    /** An encoded entity-chunk tag holding one entity whose displayed {@code "Item"} is {@code stack}. */
    private CompoundTag entityChunkDisplaying(UUID uuid, ItemStack stack) {
        CompoundTag entity = EntityFixtures.entity("minecraft:item_frame", uuid);
        entity.put("Item", itemTagOf(stack));
        return EntityFixtures.entityChunkTagWith(entity);
    }

    private CompoundTag itemTagOf(ItemStack stack) {
        return (CompoundTag) ((ListTag) holderOf(sink, registries, stack).get("Items")).get(0);
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

            remapHolderItems(session, holderReferencing(sink, registries, 5), holderPosition);
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

    /**
     * Lose one item through each of the session's three voices, so what a download's first losses carry can be compared
     * against a later download's. The map write is the real streaming path and lands on the writer thread, so the
     * writer is drained before this returns and the three lines are in a settled order.
     */
    private void loseOneItemThroughEachVoice(LiveCaptureSession session, Path save) throws Exception {
        MapArchive archive = throwingArchive();
        WorldPaths paths = new VersionAdapterImpl().worldPaths(unwritableDataRoot(save));
        AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                LiveCaptureSessionLossLogWiringTest::mapOnlyWriter);

        remapHolderItems(session, holderReferencing(sink, registries, 5), new BlockPos(4, 64, -7));
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

    /**
     * Close the finish batch, which production does only inside finish(), behind the client singleton the headless
     * session does without.
     */
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
