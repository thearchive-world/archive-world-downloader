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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SerializableUUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
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
 * Asserted against the bytes in {@code entities/} rather than against a counter or a log line, because that is the only
 * place the second copy is visible: every write succeeds, all are ordinary, and no tally moves.
 */
class EntityVehicleRelocationTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    private final ChunkPos opened = new ChunkPos(0, 0);
    private final ChunkPos drifted = new ChunkPos(1, 0);

    private static final UUID VEHICLE = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    @Test
    void aVehicleThatDriftsOneChunkIsWrittenAgainCarryingItsContents(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);

        bufferEntity(session, VEHICLE, drifted, entity(VEHICLE));
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
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);

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
        LiveCaptureSession session = session(temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));
        session.flushEntityChunk(writer, opened);
        writer.finish().get(30, TimeUnit.SECONDS);

        CompoundTag folded = session.foldRidingVehicleContents(entity(VEHICLE));

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
        AsyncSaveWriter writer = saveWriter(paths);
        bindArchive(session, new MapArchive(MapManifest.empty(), id -> null, (archiveId, dataTag) -> {}));
        stashEntityContainer(session, VEHICLE, holderReferencing(sink, registries, 4242));
        bufferEntity(session, VEHICLE, opened, entity(VEHICLE));

        session.flushEntityChunk(writer, opened);
        writer.finish().get(30, TimeUnit.SECONDS);
        int archivedId = mapIdOf(retainedHolder(session, VEHICLE));

        CompoundTag mount = session.foldRidingVehicleContents(entity(VEHICLE));

        assertEquals(archivedId, mapIdOf(mount),
                "the retained holder was already remapped, so folding it into the ridden mount must keep the "
                        + "archive id it already carries; a second pass re-resolves that id as a session id and "
                        + "the mount ends up naming another map's picture, or none");
    }

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

    private static Set<String> itemIds(CompoundTag entity) {
        Set<String> ids = new HashSet<>();
        if (entity.get("Items") instanceof ListTag items) {
            for (int i = 0; i < items.size(); i++) {
                ids.add(((CompoundTag) items.get(i)).getString("id"));
            }
        }
        return ids;
    }

    private static List<CompoundTag> entitiesOnDisk(WorldPaths paths, UUID uuid, ChunkPos... positions)
            throws Exception {
        List<CompoundTag> found = new ArrayList<>();
        try (IOWorker storage = paths.openEntitiesStorage(Level.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                CompoundTag chunkTag = Optional.ofNullable(storage.load(pos)).orElse(null);
                if (chunkTag == null || !(chunkTag.get("Entities") instanceof ListTag entities)) {
                    continue;
                }
                for (int i = 0; i < entities.size(); i++) {
                    if (entities.get(i) instanceof CompoundTag entity && uuid.equals(EntityMerge.readUuid(entity))) {
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
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static WorldPaths paths(Path save) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        Files.createDirectories(paths.regionDirectory(Level.OVERWORLD));
        Files.createDirectories(paths.entitiesDirectory(Level.OVERWORLD));
        return paths;
    }

    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                paths::openRegionStorage,
                paths::openEntitiesStorage,
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                new SaveProgress());
    }

    private static CompoundTag entity(UUID uuid) {
        CompoundTag entity = new CompoundTag();
        entity.put("UUID", SerializableUUID.CODEC.encodeStart(NbtOps.INSTANCE, uuid).getOrThrow(false, s -> {}));
        return entity;
    }

    private static CompoundTag entityWithAir(UUID uuid, short air) {
        CompoundTag entity = entity(uuid);
        entity.putShort("Air", air);
        return entity;
    }

    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items, registries);
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

    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }
}
