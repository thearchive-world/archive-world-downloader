// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

class LiveCaptureSessionMapTallyTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
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
        assertTrue(config.remapMapIds(), "the manifest write path is the remap-on arm, so the fixture needs it on");
        RegistryAccess registries = TestRegistries.frozen();
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, registries,
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static AsyncSaveWriter mapOnlyWriter() {
        return new AsyncSaveWriter(
                dimension -> {
                    throw new AssertionError("a map write must not open a region storage");
                },
                dimension -> {
                    throw new AssertionError("a map write must not open an entities storage");
                },
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                new SaveProgress());
    }

    private static Path unwritableDataRoot(Path save) throws Exception {
        Files.createDirectories(save);
        Files.writeString(save.resolve("data"), "not a directory");
        return save;
    }

    private static MapArchive stubArchive() {
        return new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {});
    }

    private static CompoundTag mapData() {
        CompoundTag data = new CompoundTag();
        data.putString("dimension", "minecraft:overworld");
        data.putByteArray("colors", new byte[16384]);
        return data;
    }

    @Test
    void aStreamedMapWriteFailureLandsInTheSessionsOwnTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        Path save = unwritableDataRoot(temporary.resolve("save"));
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        assertFalse(session.isPartialSave(0, 0), "nothing has been streamed, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, stubArchive(),
                LiveCaptureSessionMapTallyTest::mapOnlyWriter);

        session.streamMapData(7, mapData());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
        assertTrue(session.isPartialSave(0, 0),
                "the streamed write failed, so the task counted into a tally this session's verdict reads");
    }

    @Test
    void anIdCountsWriteBlockedAtItsStagedFileLeavesThePriorFloorOnDisk(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        MapDataWriter.writeIdCounts(paths.dataDirectory(), MapDataWriter.serializeIdCounts(500));
        // A directory where the staged sibling belongs: only the staged route fails here, so this is what
        // separates it from a direct write.
        Files.createDirectory(paths.dataDirectory().resolve("idcounts.dat.tmp"));
        MapArchive archive = stubArchive();
        archive.archiveIdFor(42);
        assertFalse(session.isPartialSave(0, 0), "nothing has been written, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                LiveCaptureSessionMapTallyTest::mapOnlyWriter);

        session.writeIdCounts(paths);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
        assertEquals(500, MapDataWriter.readIdCounts(paths.dataDirectory()),
                "the finalize write failed at its staged file, so the floor already on disk is untouched");
        assertTrue(session.isPartialSave(0, 0),
                "the failed idcounts write still reaches the partial verdict through its own counter");
        assertEquals(0, mapsFailed(session),
                "no captured map was lost, so the failure never inflates the count of lost maps");
    }

    @Test
    void anIdCountsWriteFailureTurnsTheVerdictPartialButCountsNoLostMap(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        Path save = unwritableDataRoot(temporary.resolve("save"));
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        MapArchive archive = stubArchive();
        archive.archiveIdFor(42); // an id referenced this session is what gives idCountsTag a value to write
        assertFalse(session.isPartialSave(0, 0), "nothing has been written, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, archive,
                LiveCaptureSessionMapTallyTest::mapOnlyWriter);

        session.writeIdCounts(paths);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
        assertTrue(session.isPartialSave(0, 0),
                "the finalize-time idcounts write failed, so it reaches the partial verdict through its own "
                        + "counter rather than inflating the count of lost maps");
        assertEquals(0, mapsFailed(session),
                "no captured map was lost, so the map count stays zero and never sends a reader hunting the log "
                        + "for a per-item line that was never written");
    }

    private static int mapsFailed(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapsFailed");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(session)).get();
    }

    @Test
    void aMapImagedAfterTheFinishBatchClosedLandsInTheSessionsOwnTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        AsyncSaveWriter writer = session.bindWorldOpen(paths, stubArchive(),
                LiveCaptureSessionMapTallyTest::mapOnlyWriter);
        closeTheFinishBatch(session);
        assertFalse(session.isPartialSave(0, 0), "nothing has been streamed, so the finish reads clean");

        session.streamMapData(7, mapData());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
        assertEquals(1, mapsFailed(session),
                "the batch it would have joined is gone, so the map is counted lost rather than dropped quietly");
        assertTrue(session.isPartialSave(0, 0), "and one lost map is enough to make the finish partial");
    }

    @Test
    void aStreamedMapWriteReachesDiskThroughTheBoundWriter(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        AsyncSaveWriter writer = session.bindWorldOpen(paths, stubArchive(),
                LiveCaptureSessionMapTallyTest::mapOnlyWriter);

        session.streamMapData(7, mapData());
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
        assertTrue(Files.exists(paths.dataDirectory().resolve("map_7.dat")),
                "the bind published the writer, so the streamed map was written rather than counted lost");
        assertFalse(session.isPartialSave(0, 0), "a map that reached disk is no loss, so the finish reads clean");
    }

    private static void closeTheFinishBatch(LiveCaptureSession session) throws Exception {
        Field closed = LiveCaptureSession.class.getDeclaredField("finishBatchClosed");
        closed.setAccessible(true);
        closed.setBoolean(session, true);
    }

    @Test
    void theMapTallyTurnsTheVerdictPartialAndKeepsReadingPartial(@TempDir Path temporary) {
        LiveCaptureSession session = session(temporary);
        assertFalse(session.isPartialSave(0, 0), "a session that lost no map reads clean");

        session.streamMapData(7, mapData()); // no writer is bound, the counted branch for a map with nowhere to go

        assertTrue(session.isPartialSave(0, 0), "one lost map is enough to make the finish partial");
        assertTrue(session.isPartialSave(0, 0),
                "the verdict is read twice on a real finish (the finalizer and the main-thread fallback), so "
                        + "reading it must not consume the count");
    }

    @Test
    void aManifestWriteFailureLandsInTheManifestTallyAndTurnsTheVerdictPartial(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        session.bindWorldOpen(paths, stubArchive(), LiveCaptureSessionMapTallyTest::mapOnlyWriter);
        bindManifestFile(session, unwritableManifestFile(temporary));
        assertFalse(session.isPartialSave(0, 0), "nothing has been written, so the finish reads clean");

        session.saveMapManifest();

        assertTrue(stale(session),
                "the manifest never reached disk, so the resume that will re-image every map is recorded");
        assertEquals(0, mapsFailed(session),
                "no captured map was lost, so the map count stays zero and never sends a reader hunting the log "
                        + "for a per-item line that was never written");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    /**
     * What the degradation must not do is restart the id counter, whose high-water lives in the file that just failed
     * to read: an unseeded counter hands id 0 to the first map this session images, and that write lands on the prior
     * download's map_0.dat.
     */
    @Test
    void aManifestReadFailureStillIssuesIdsAboveTheMapFilesTheFolderHolds(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        Path dataDirectory = Files.createDirectories(temporary.resolve("save").resolve("data"));
        MapDataWriter.write(dataDirectory, "map_4", mapData()); // a prior download's highest imaged map
        // A directory where the manifest file belongs: it exists, so the loader gets past its absent-file
        // fast path and faults on the read itself.
        Path unreadable = Files.createDirectories(temporary.resolve("map-ids"));
        assertFalse(session.isPartialSave(0, 0), "nothing has been read, so the finish reads clean");

        MapManifest manifest = session.loadManifest(unreadable, dataDirectory);

        assertEquals(4, manifest.highestAssignedId(),
                "the read degraded to an empty manifest rather than stopping the download, but one seeded so the"
                        + " reopened world's allocator still clears map_4.dat");
        assertEquals(5, manifest.lookupOrInsert("a-map-this-session-images"),
                "so the first map this session images takes an id no existing map file holds");
        assertEquals(1, losses(session, "mapManifestReadFailed"),
                "and the degradation is counted, so the download it silently re-images maps in reports partial");
        assertTrue(session.isPartialSave(0, 0), "which makes the finish partial rather than clean");
    }

    @Test
    void aManifestReadFailureTakesItsSeedFromIdCountsWhenNoMapFileCarriesIt(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        Path dataDirectory = Files.createDirectories(temporary.resolve("save").resolve("data"));
        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(9));
        Path unreadable = Files.createDirectories(temporary.resolve("map-ids"));

        MapManifest manifest = session.loadManifest(unreadable, dataDirectory);

        assertEquals(9, manifest.highestAssignedId(),
                "no map file carries the floor, so the seed comes off the idcounts high-water instead");
        assertEquals(10, manifest.lookupOrInsert("a-map-this-session-images"),
                "and the ids issued clear the imageless one the prior download allocated");
    }

    @Test
    void aManifestWriteRepairedLaterInTheSameDownloadLeavesTheVerdictClean(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        session.bindWorldOpen(paths, stubArchive(), LiveCaptureSessionMapTallyTest::mapOnlyWriter);
        bindManifestFile(session, unwritableManifestFile(temporary));

        session.saveMapManifest();
        assertTrue(session.isPartialSave(0, 0), "the first write failed, so the finish reads partial for now");
        bindManifestFile(session, temporary.resolve("save").resolve("map-ids"));
        session.saveMapManifest();

        assertFalse(stale(session), "the second write put the manifest on disk, so nothing is stale at the end");
        assertTrue(Files.exists(temporary.resolve("save").resolve("map-ids")),
                "and it really reached disk, so the cleared flag is not a flag cleared over a missing file");
        assertFalse(session.isPartialSave(0, 0),
                "a download whose manifest is correct when it ends lost nothing here, so it reads clean");
    }

    private static Path unwritableManifestFile(Path temporary) throws Exception {
        Path blocker = temporary.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        return blocker.resolve("map-ids");
    }

    private static void bindManifestFile(LiveCaptureSession session, Path file) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapIdsFile");
        field.setAccessible(true);
        field.set(session, file);
    }

    /**
     * Read reflectively because the verdict cannot tell one term of its sum from another: a site rewired to a sibling
     * counter still reads partial, so pinning the verdict alone would pass on the mistake.
     */
    private static int losses(LiveCaptureSession session, String field) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField(field);
        counter.setAccessible(true);
        return counter.getInt(session);
    }

    private static boolean stale(LiveCaptureSession session) throws Exception {
        Field flag = LiveCaptureSession.class.getDeclaredField("mapManifestStale");
        flag.setAccessible(true);
        return flag.getBoolean(session);
    }

    @Test
    void theWorldOpenBindPublishesTheArchiveBeforeTheWriterExists(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = new VersionAdapterImpl().worldPaths(temporary.resolve("save"));
        MapArchive archive = stubArchive();
        archive.archiveIdFor(42); // an id referenced this session is what gives idCountsTag a value to write
        Supplier<AsyncSaveWriter> counted = () -> {
            session.writeIdCounts(paths);
            return mapOnlyWriter();
        };

        AsyncSaveWriter writer = session.bindWorldOpen(paths, archive, counted);

        assertTrue(Files.exists(paths.dataDirectory().resolve("idcounts.dat")),
                "the archive was bound before the factory ran, so the write it drove found one");
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);
        assertFalse(result.failed(), "the drain reached no region storage, so no opener guard fired");
    }
}
