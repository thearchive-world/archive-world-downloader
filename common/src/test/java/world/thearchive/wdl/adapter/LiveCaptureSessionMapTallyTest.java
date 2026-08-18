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

/**
 * The session's map-loss tally, pinned at the joints the leaf-level {@link LiveCaptureSession#mapWriteTask} test cannot
 * see: that the streamed write task is handed the session's own counter, that a map imaged after the finish batch
 * closed is counted rather than only logged, that the finalize-time idcounts failure reaches the same partial verdict
 * through a counter of its own, that the map-id manifest's read and write do the same through a third, and that a
 * non-zero count is what turns the completion record from clean to partial. Break any of those and a download that
 * silently lost maps still reports clean, except the idcounts and manifest ones, where the loss is instead a download
 * whose map ids may be reissued or a folder that will re-image every map it already holds, or an unrelated failure
 * inflating the count of maps a reader is told to go find in the log. Four tests are not tally tests: one pins the
 * ordering inside the world-open seam the others bind through, one pins that a streamed map actually reaches disk,
 * without which every tally test here would also pass on a bind that published no writer at all, one pins that the
 * finalize floor write is the staged one, and one pins the second source the degraded manifest's id floor is seeded
 * from.
 *
 * <p>Two boundaries. The tally is pinned from {@code streamMapData} inward: the archive is built here with a stub sink,
 * so nothing in this class would notice if the production wiring stopped handing {@code MapArchive} the session's own
 * {@code streamMapData}. That mutation is caught, but a tier away, by the filled-map gametests that read
 * {@code data/map_*.dat} back off disk. And the loud failure that {@code level()} exists for is unreachable from this
 * tier, because every path to it needs a client, so the nullable field's guarantee rests on review rather than on a
 * test. Closing either here needs the level seam these tests deliberately do without.
 */
class LiveCaptureSessionMapTallyTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // vanilla statics, which a test reaching a map write needs before it runs
    }

    /**
     * A session with no bound level, which is all the map paths need. Entity and container capture are off so the
     * constructor publishes no process-wide capture into the static activation slots; a test that turns either on
     * leaves a session published there for the rest of the suite JVM, since only {@code finish()} clears it. The
     * toggles are asserted rather than assumed: an unrecognized config key falls back to the default, which is on for
     * both, so a silent key rename would arm every session this fixture builds.
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

    /** A regular file where the {@code data/} directory belongs, so every write under it fails at open. */
    private static Path unwritableDataRoot(Path save) throws Exception {
        Files.createDirectories(save);
        Files.writeString(save.resolve("data"), "not a directory");
        return save;
    }

    /** An archive whose image resolver and data sink are both inert, so a test drives the session directly. */
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

    /**
     * The session's lost-map count. Read reflectively because folding the idcounts counter back into it is the
     * tidy-looking edit that would silently break the invariant this asserts, and the verdict cannot see it: the two
     * counters sum into one partial-or-clean answer either way.
     */
    private static int mapsFailed(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapsFailed");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(session)).get();
    }

    /**
     * A map imaged after the finish batch closed has nowhere to go, and the count of it is what the log line beside it
     * cannot stand in for: the wiring test pins that line, so without this the increment could be dropped and only a
     * reader of the log would ever know the map was lost.
     */
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

    /**
     * The streamed write reaches disk, which is what tells the counted branches apart from each other: every other map
     * test asserts a non-zero tally, and the no-writer branch increments that same tally, so all of them would still
     * pass if the world-open bind stopped publishing the writer and no map were ever written.
     */
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

    /**
     * Close the finish batch, which production does only inside {@code finish()}, behind the client singleton the
     * headless session does without.
     */
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

    /**
     * The manifest write sits beside the idcounts write and carries the same kind of shared-file consequence. That
     * consequence is the folder's, not one map's: without a rewritten manifest a resume can no longer tell an archived
     * id from a fresh one, so it re-images every map the folder already holds.
     */
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
     * The read sibling of the write above, which degrades to an empty manifest and carries the same consequence for the
     * folder: the download proceeds treating every map it already holds as new. What the degradation must not do is
     * restart the id counter, whose high-water lives in the file that just failed to read: an unseeded counter hands id
     * 0 to the first map this session images, and that write lands on the prior download's map_0.dat.
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

    /**
     * The second, independent source for that seed. A prior download's highest id can be an imageless one, which
     * allocates a counter id and writes no map file, so the map files alone would under-read the floor and the seeded
     * counter would reissue it.
     */
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

    /**
     * The repair the write record is a flag rather than a tally for: the same file is written at world-open and again
     * at finalize, so a fault at the first that the second heals cost the download nothing. A tally would have reported
     * that download partial, and reported two losses for one file when both writes failed.
     */
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

    /** A manifest path whose parent is a regular file, so staging the atomic write under it fails at open. */
    private static Path unwritableManifestFile(Path temporary) throws Exception {
        Path blocker = temporary.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        return blocker.resolve("map-ids");
    }

    /** Bind the manifest path, which production resolves in the world-open step behind the client singleton. */
    private static void bindManifestFile(LiveCaptureSession session, Path file) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapIdsFile");
        field.setAccessible(true);
        field.set(session, file);
    }

    /**
     * The session's loss counter named {@code field}. Read reflectively because the verdict cannot tell one term of its
     * sum from another: a site rewired to a sibling counter still reads partial, so pinning the verdict alone would
     * pass on the mistake.
     */
    private static int losses(LiveCaptureSession session, String field) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField(field);
        counter.setAccessible(true);
        return counter.getInt(session);
    }

    /** Whether the session records the manifest on disk as stale, read for the same reason the counters are. */
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
        // Inside the factory is the one moment where the publication order is observable, and it is the order
        // the whole parameter shape exists for: the finalizer reads this field on the writer thread, and
        // world-open earns that edge by assigning it before the writer exists. A write driven from here lands
        // on disk only if the bind assigned the archive first. The paths half is not pinned: writeIdCounts
        // takes them as an argument, and every in-class reader of the field needs state this moment lacks.
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
