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
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
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
 * Asserted against the region chunk's {@code Level.Entities} bytes rather than against a counter or a log line, because
 * that is the only place the second copy is visible: every write succeeds, all are ordinary, and no tally moves.
 */
class EntityVehicleRelocationTest {
    private final ContainerSink sink = new ContainerSinkImpl();

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
        seedHosts(paths, opened, drifted);
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);

        bufferEntity(session, VEHICLE, drifted, entity(VEHICLE));
        session.flushEntityChunk(writer, drifted);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<NBTTagCompound> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(2, written.size(), "the vehicle is written where it now is as well as where it was");
        assertTrue(written.stream().allMatch(EntityMerge::hasCapturedContent),
                "and BOTH copies carry the contents, so which one vanilla keeps on load stops mattering");
    }

    @Test
    void aReFlushOfTheSameChunkStillWritesAndKeepsTheContents(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted);
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);
        // The second copy carries a field the first does not, so the assertion can tell a real re-write apart from a
        // suppressed one.
        bufferEntity(session, VEHICLE, opened, entityWithAir(VEHICLE, (short) 42));
        session.flushEntityChunk(writer, opened);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<NBTTagCompound> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(1, written.size(), "one chunk file, one copy");
        assertEquals((short) 42, written.get(0).hasKey("Air") ? written.get(0).getShort("Air") : (short) -1,
                "the re-flush of the same chunk is written, since the read-merge makes it lossless");
        assertTrue(EntityMerge.hasCapturedContent(written.get(0)),
                "and the entity read-merge carries the contents into that re-written copy");
    }

    @Test
    void aVehicleReopenedAfterItMovesIsWrittenWithTheContentsThatOpenCaptured(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted);
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);

        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.EMERALD, 3)));
        bufferEntity(session, VEHICLE, drifted, entity(VEHICLE));
        session.flushEntityChunk(writer, drifted);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error");
        List<NBTTagCompound> written = entitiesOnDisk(paths, VEHICLE, opened, drifted);
        assertEquals(2, written.size(), "a re-open is a fresh capture, so it is written where the vehicle now is");
        assertTrue(written.stream().allMatch(EntityMerge::hasCapturedContent),
                "and neither copy is the empty one this guard exists to prevent");
        assertTrue(itemIds(entitiesOnDisk(paths, VEHICLE, drifted).get(0)).contains("minecraft:emerald"),
                "the copy written after the re-open carries what the re-open saw, not the stale retained holder");
    }

    @Test
    void aReboardedMountIsWrittenWholeRatherThanSkipped(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted);
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));
        session.flushEntityChunk(writer, opened);
        writer.finish().get(30, TimeUnit.SECONDS);

        NBTTagCompound folded = session.foldRidingVehicleContents(entity(VEHICLE));

        assertTrue(EntityMerge.hasCapturedContent(folded),
                "the mount the player is riding at finish carries the contents its earlier open captured");
        assertTrue(itemIds(folded).contains("minecraft:diamond"), "and they are the items that were captured");
    }

    @Test
    void aReboardedMountsMapIsNotRemappedTwice(@TempDir Path temporary) throws Exception {
        // The map remap's guard is an identity-keyed set, which means anything that re-copies a retained holder
        // silently defeats it.
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        seedHosts(paths, opened, drifted);
        AsyncSaveWriter writer = saveWriter(paths);
        bindArchive(session, new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {}));
        stashEntityContainer(session, VEHICLE, holderReferencing(sink, 4242));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);
        writer.finish().get(30, TimeUnit.SECONDS);
        int archivedId = mapIdOf(retainedHolder(session, VEHICLE));

        NBTTagCompound mount = session.foldRidingVehicleContents(entity(VEHICLE));

        assertEquals(archivedId, mapIdOf(mount),
                "the retained holder was already remapped, so folding it into the ridden mount must keep the "
                        + "archive id it already carries; a second pass re-resolves that id as a session id and "
                        + "the mount ends up naming another map's picture, or none");
    }

    private static int mapIdOf(NBTTagCompound tag) {
        NBTTagList items = (NBTTagList) tag.getTag("Items");
        return ((NBTTagCompound) items.get(0)).getShort("Damage");
    }

    @SuppressWarnings("unchecked")
    private static NBTTagCompound retainedHolder(LiveCaptureSession session, UUID uuid) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("foldedContainerVehicles");
        field.setAccessible(true);
        return ((Map<UUID, NBTTagCompound>) field.get(session)).get(uuid);
    }

    private static void bindArchive(LiveCaptureSession session, MapArchive archive) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("mapArchive");
        field.setAccessible(true);
        field.set(session, archive);
    }

    private static Set<String> itemIds(NBTTagCompound entity) {
        Set<String> ids = new HashSet<>();
        if (entity.getTag("Items") instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) entity.getTag("Items");
            for (int i = 0; i < items.tagCount(); i++) {
                ids.add(((NBTTagCompound) items.get(i)).getString("id"));
            }
        }
        return ids;
    }

    private static List<NBTTagCompound> entitiesOnDisk(WorldPaths paths, UUID uuid, ChunkPos... positions)
            throws Exception {
        List<NBTTagCompound> found = new ArrayList<>();
        try (WdlRegionStorage storage = paths.openRegionStorage(DimensionType.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                NBTTagCompound chunkTag = Optional.ofNullable(storage.read(pos)).orElse(null);
                if (chunkTag == null || !(chunkTag.getCompoundTag("Level").getTag("Entities") instanceof NBTTagList)) {
                    continue;
                }
                NBTTagList entities = (NBTTagList) chunkTag.getCompoundTag("Level").getTag("Entities");
                for (int i = 0; i < entities.tagCount(); i++) {
                    NBTTagCompound entity = entities.get(i) instanceof NBTTagCompound ? (NBTTagCompound) entities.get(i)
                            : null;
                    if (entity != null && uuid.equals(EntityMerge.readUuid(entity))) {
                        found.add(entity);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots, which only {@code finish()} clears; the flush entry point itself reads neither toggle.
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
                NBTTagCompound host = new NBTTagCompound();
                host.setTag("Level", new NBTTagCompound());
                region.write(pos, host);
            }
        }
    }

    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                paths::openRegionStorage,
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                new SaveProgress());
    }

    private static NBTTagCompound entity(UUID uuid) {
        NBTTagCompound entity = new NBTTagCompound();
        entity.setUniqueId("UUID", uuid);
        return entity;
    }

    private static NBTTagCompound entityWithAir(UUID uuid, short air) {
        NBTTagCompound entity = entity(uuid);
        entity.setShort("Air", air);
        return entity;
    }

    private NBTTagCompound capturedItems(ItemStack stack) {
        ItemStack[] items = new ItemStack[27];
        items[0] = stack;
        return sink.captureItems(items);
    }

    private static void stashEntityContainer(LiveCaptureSession session, UUID uuid, NBTTagCompound holder)
            throws Exception {
        Map<UUID, NBTTagCompound> stash = state(session, "entityContainerStash");
        stash.put(uuid, holder);
    }

    private static void bufferEntity(LiveCaptureSession session, UUID uuid, ChunkPos pos, NBTTagCompound tag)
            throws Exception {
        EntityBuffer buffer = state(session, "entityBuffer");
        buffer.accumulate(uuid, pos, tag);
    }

    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }
}
