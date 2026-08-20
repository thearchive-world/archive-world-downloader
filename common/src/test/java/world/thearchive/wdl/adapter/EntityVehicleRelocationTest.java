// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderReferencing;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
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
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The on-disk guard for a container vehicle that moves between two entity-chunk flushes. Its captured contents exist
 * only in the stash, which the first fold drains, so every later serialize of the same vehicle is empty; the entity
 * read-merge carries {@code "Items"} forward only inside the chunk file it is writing, so a copy filed under a new
 * chunk key would otherwise be a second on-disk entity with the same UUID and no contents, and vanilla keeps whichever
 * loads first. The remedy pinned here is that the retained holder is re-folded into every later copy, so which one
 * survives stops mattering.
 *
 * <p>Asserted against the region chunk's {@code Level.Entities} bytes rather than against a counter or a log line,
 * because that is the only place the second copy is visible: every write succeeds, all are ordinary, and no tally
 * moves. Each case reads every chunk the flushes could have touched and inspects the UUID in all of them.
 *
 * <p>Suppressing the second write instead was tried and rejected: it dropped whatever else the later tag carried, most
 * sharply a passenger that boarded since, which reaches disk nowhere but nested in its vehicle.
 */
class EntityVehicleRelocationTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    // Instance fields, not constants: ChunkPos's own class initializer reaches a built-in registry, so touching
    // one before the bootstrap in @BeforeAll fails the whole class with "Not bootstrapped".
    private final ChunkPos opened = new ChunkPos(0, 0);
    private final ChunkPos drifted = new ChunkPos(1, 0);

    private static final UUID VEHICLE = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aVehicleThatDriftsOneChunkIsWrittenAgainCarryingItsContents(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted); // entities fold into their host chunks
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened); // the player opened it here; the fold drains the stash

        bufferEntity(session, VEHICLE, drifted, entity(VEHICLE)); // re-announced a chunk over, nothing left to fold
        session.flushEntityChunk(writer, drifted);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<CompoundTag> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(2, written.size(), "the vehicle is written where it now is as well as where it was");
        assertTrue(written.stream().allMatch(EntityMerge::hasCapturedContent),
                "and BOTH copies carry the contents, so which one vanilla keeps on load stops mattering");
    }

    @Test
    void aReFlushOfTheSameChunkStillWritesAndKeepsTheContents(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted); // entities fold into their host chunks
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);
        // The second copy carries a field the first does not, so the assertion can tell a real re-write apart
        // from a suppressed one: without it the first write's bytes would satisfy every check on their own.
        bufferEntity(session, VEHICLE, opened, entityWithAir(VEHICLE, (short) 42));
        session.flushEntityChunk(writer, opened);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<CompoundTag> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(1, written.size(), "one chunk file, one copy");
        assertEquals((short) 42, written.get(0).contains("Air") ? written.get(0).getShort("Air") : (short) -1,
                "the re-flush of the same chunk is written, since the read-merge makes it lossless");
        assertTrue(EntityMerge.hasCapturedContent(written.get(0)),
                "and the entity read-merge carries the contents into that re-written copy");
    }

    @Test
    void aVehicleReopenedAfterItMovesIsWrittenWithTheContentsThatOpenCaptured(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted); // entities fold into their host chunks
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);

        // The player catches up with the boat and opens it again: the fresh capture has contents of its own, so
        // suppressing this write would drop the newer loot on the floor.
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.EMERALD, 3)));
        bufferEntity(session, VEHICLE, drifted, entity(VEHICLE));
        session.flushEntityChunk(writer, drifted);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<CompoundTag> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(2, written.size(), "a re-open is a fresh capture, so it is written where the vehicle now is");
        assertTrue(written.stream().allMatch(EntityMerge::hasCapturedContent),
                "and neither copy is the empty one this guard exists to prevent");
        assertTrue(itemIds(entitiesOnDisk(paths, VEHICLE, drifted).get(0)).contains("minecraft:emerald"),
                "the copy written after the re-open carries what the re-open saw, not the stale retained holder");
    }

    @Test
    void aReboardedMountIsWrittenWholeRatherThanSkipped(@TempDir Path temporary) throws Exception {
        // The finish-time ridden-mount path used to give up when the stash had already drained into an earlier
        // standalone write: it skipped the RootVehicle entirely, which took the mount's finish position and any
        // passenger aboard with it. The retained holder is exactly what that path was missing.
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted); // entities fold into their host chunks
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));
        session.flushEntityChunk(writer, opened); // drains the stash, retains the holder
        writer.finish().get(30, TimeUnit.SECONDS);

        CompoundTag folded = session.foldRidingVehicleContents(entity(VEHICLE));

        assertTrue(EntityMerge.hasCapturedContent(folded),
                "the mount the player is riding at finish carries the contents its earlier open captured");
        assertTrue(itemIds(folded).contains("minecraft:diamond"), "and they are the items that were captured");
    }

    @Test
    void aReboardedMountsMapIsNotRemappedTwice(@TempDir Path temporary) throws Exception {
        // The map remap is documented non-idempotent: a second pass reads an already-archived id as a session
        // id and re-resolves it, so the mount's map ends up naming a different picture or no file at all. The
        // guard is an identity-keyed set, which means anything that re-copies a retained holder silently
        // defeats it, and level.dat is where the damage lands.
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted); // entities fold into their host chunks
        AsyncSaveWriter writer = saveWriter(paths);
        bindArchive(session, new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {}));
        stashEntityContainer(session, VEHICLE, holderReferencing(sink, 4242));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened); // remaps once and retains the holder
        writer.finish().get(30, TimeUnit.SECONDS);
        int archivedId = mapIdOf(retainedHolder(session, VEHICLE));

        CompoundTag mount = session.foldRidingVehicleContents(entity(VEHICLE));

        assertEquals(archivedId, mapIdOf(mount),
                "the retained holder was already remapped, so folding it into the ridden mount must keep the "
                        + "archive id it already carries; a second pass re-resolves that id as a session id and "
                        + "the mount ends up naming another map's picture, or none");
    }

    /** The map id the first item of a tag's container contents references (below 1.20.5, the raw {@code "map"} tag). */
    private static int mapIdOf(CompoundTag tag) {
        ListTag items = (ListTag) tag.get("Items");
        CompoundTag itemTag = (CompoundTag) ((CompoundTag) items.get(0)).get("tag");
        return itemTag.getInt("map");
    }

    @SuppressWarnings("unchecked")
    private static CompoundTag retainedHolder(LiveCaptureSession session, UUID uuid) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("foldedContainerVehicles");
        field.setAccessible(true);
        return ((Map<UUID, CompoundTag>) field.get(session)).get(uuid);
    }

    private static void bindArchive(LiveCaptureSession session, MapArchive archive) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapArchive");
        field.setAccessible(true);
        field.set(session, archive);
    }

    /** The item ids of an entity tag's container contents, for telling one capture of a vehicle from another. */
    private static Set<String> itemIds(CompoundTag entity) {
        Set<String> ids = new HashSet<>();
        if (entity.get("Items") instanceof ListTag) {
            ListTag items = (ListTag) entity.get("Items");
            for (int i = 0; i < items.size(); i++) {
                ids.add(((CompoundTag) items.get(i)).getString("id"));
            }
        }
        return ids;
    }

    /**
     * Every on-disk entity tag carrying {@code uuid}, across each region chunk's Level.Entities the flushes reached.
     */
    private static List<CompoundTag> entitiesOnDisk(WorldPaths paths, UUID uuid, ChunkPos... positions)
            throws Exception {
        List<CompoundTag> found = new ArrayList<>();
        try (WdlRegionStorage storage = paths.openRegionStorage(DimensionType.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                CompoundTag chunkTag = Optional.ofNullable(storage.read(pos)).orElse(null);
                if (chunkTag == null || !(chunkTag.getCompound("Level").get("Entities") instanceof ListTag)) {
                    continue;
                }
                ListTag entities = (ListTag) chunkTag.getCompound("Level").get("Entities");
                for (int i = 0; i < entities.size(); i++) {
                    CompoundTag entity = entities.get(i) instanceof CompoundTag ? (CompoundTag) entities.get(i) : null;
                    if (entity != null && uuid.equals(EntityMerge.readUuid(entity))) {
                        found.add(entity);
                    }
                }
            }
        }
        return found;
    }

    /**
     * A session with no bound level, which is all the entity flush needs. Entity and container capture are off so the
     * constructor publishes no process-wide capture into the static activation slots, which only {@code finish()}
     * clears; the flush entry point itself reads neither toggle.
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

    private static WorldPaths paths(Path save) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        Files.createDirectories(paths.regionDirectory(DimensionType.OVERWORLD));
        return paths;
    }

    /** Seed a host region chunk at each position so the entity folds have terrain to land inside. */
    private static void seedHosts(WorldPaths paths, ChunkPos... positions) throws Exception {
        try (WdlRegionStorage region = paths.openRegionStorage(DimensionType.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                CompoundTag host = new CompoundTag();
                host.put("Level", new CompoundTag());
                region.write(pos, host);
            }
        }
    }

    /** A writer over the real region storage, since the duplicate is only visible in the written bytes. */
    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                paths::openRegionStorage,
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                new SaveProgress());
    }

    /** A serialized entity tag carrying just its UUID, which is all the folds and the envelope read. */
    private static CompoundTag entity(UUID uuid) {
        CompoundTag entity = new CompoundTag();
        entity.putUUID("UUID", uuid);
        return entity;
    }

    /** The same tag plus a vanilla field the readback can distinguish one serialize of an entity from another by. */
    private static CompoundTag entityWithAir(UUID uuid, short air) {
        CompoundTag entity = entity(uuid);
        entity.putShort("Air", air);
        return entity;
    }

    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items);
    }

    private static void stashEntityContainer(LiveCaptureSession session, UUID uuid, CompoundTag holder)
            throws Exception {
        Map<UUID, CompoundTag> stash = state(session, "entityContainerStash");
        stash.put(uuid, holder);
    }

    private static void bufferEntity(LiveCaptureSession session, UUID uuid, ChunkPos pos, CompoundTag tag)
            throws Exception {
        EntityBuffer buffer = state(session, "entityBuffer");
        buffer.accumulate(uuid, pos, tag);
    }

    /**
     * One of the session's capture-state collections, to seed. Reflective because every production path that fills one
     * runs behind the client singleton (a menu open, a packet-driven promote), and widening the fields would publish
     * mutable capture state to the whole package for a test's convenience.
     */
    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }
}
