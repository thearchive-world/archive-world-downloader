// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.LevelDataWriterImpl;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SaveProgress;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.WorldOutputConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The one path that carries a previous download's parked mount forward. A mount ridden at the end of a download is
 * written to no entities region at all, because a vehicle with exactly one player passenger fails vanilla's own save
 * gate, so its single copy is that session's level.dat RootVehicle. A resume that finishes un-seated rewrites the
 * level.dat with no RootVehicle, which destroys that copy, and the release is what puts the mount into the world as a
 * standalone entity before that happens. Everything it fails to write is gone for good, including whatever the previous
 * download archived inside a chest boat or a chested animal.
 *
 * <p>Three axes. ROUTING: the position and the dimension must come from the same tag, so the cross-dimension case
 * asserts both the arrival and the absence, since a write that reaches the right folder while also reaching the wrong
 * one is still a corrupt save. IDENTITY: the release must decline exactly when this finish already saved the prior
 * mount inside the player's own RootVehicle, which is a question about the whole captured mount tree rather than its
 * root, so the two mounts here share an entity type and differ only by UUID and one of them rides nested inside the
 * other. COUNTING: each exit that reaches the release proper and writes nothing is asserted twice over, on the named
 * counter and on the verdict the completion record stamps from, since a counter no term of the sum reads reports
 * nothing; and every no-op is asserted in the other direction, on the absence of the WRITE rather than the absence of a
 * loss, because a path that wrongly runs and succeeds also loses nothing.
 *
 * <p>The release is driven directly, package-private for that, because every production caller runs behind the client
 * singleton; what that leaves unpinned is {@code finish()} calling it at all, and calling it in the right place, which
 * only the gametest tier can reach. The prior level.dat is a real compressed file read through the production reader,
 * and the writer is real over real region storages, so the dimension a submit routes to is read back off disk rather
 * than from a recorded call.
 */
class LiveCaptureSessionResumedMountReleaseTest {
    private static final UUID MOUNT = UUID.fromString("f4c1a8d2-3b76-4e05-9a1c-8d2e6b70f513");
    private static final UUID OTHER_MOUNT = UUID.fromString("0a7e2c95-641b-4d38-8f02-b3d54e18a7c6");
    private static final UUID PRIOR_PLAYER = UUID.fromString("2b9d6f10-84c3-4a57-9e21-7c0f5a3b8d64");
    private static final String MOUNT_LOOT = "minecraft:diamond";
    // x is negative and just below a chunk boundary, so flooring and truncating land in DIFFERENT chunks and a
    // locator that casts instead of flooring misses. x and z also differ, so an axis swap misses too.
    private static final double MOUNT_X = -1120.5;
    private static final double MOUNT_Y = 71.0;
    private static final double MOUNT_Z = 487.5;

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aMountParkedInAnotherDimensionIsReleasedIntoThatDimension(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        CompoundTag released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "the mount belongs in the dimension the previous download parked it in");
        CompoundTag mount = soleEntity(released);
        assertEquals(MOUNT.toString(), EntityMerge.readUuid(mount).toString(),
                "and it is the mount, not something else");
        assertEquals(MOUNT_LOOT, soleItemId(mount),
                "carrying the loot the previous download archived in it, which is what its loss costs");
        assertNull(entityChunk(paths, DimensionType.OVERWORLD, mountChunk()),
                "and nowhere else: the dimension this session finished in never held it");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aMountParkedInThisDimensionIsStillReleasedHere(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        writePriorLevelDat(session, save, DimensionType.OVERWORLD, mount());
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.OVERWORLD, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        CompoundTag released = entityChunk(paths, DimensionType.OVERWORLD, mountChunk());
        assertNotNull(released, "routing by the prior tag must not break the case where the two agree");
        assertEquals(MOUNT.toString(), soleEntityUuid(released), "and it is the mount");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aMountWhoseDimensionThisDownloadCannotRouteCountsAsLost(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        // A prior tag naming a dimension no download of ours writes. Writing it into the dimension this
        // session finished in is exactly the wrong-target write the routing exists to stop, so the mount is
        // reported lost rather than put somewhere plausible.
        CompoundTag prior = priorPlayerTag(DimensionType.NETHER, mount());
        prior.putString("Dimension", "examplepack:skylands");
        writePriorPlayerTag(session, save, prior);
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        assertEquals(1, losses(session), "the mount is gone and the download has to say so");
        assertTrue(session.isPartialSave(0, 0), "so the finish verdict reads partial");
        assertNull(entityChunk(paths, DimensionType.OVERWORLD, mountChunk()),
                "and nothing is written into the dimension this session merely happened to end in");
    }

    @Test
    void aMountWithNoReadablePositionCountsAsLost(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER,
                EntityFixtures.entityWithShortPos("minecraft:chest_boat", MOUNT, MOUNT_X, MOUNT_Y));
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        assertEquals(1, losses(session), "a mount with nowhere to go is still a mount that is gone");
        assertTrue(session.isPartialSave(0, 0), "so the finish verdict reads partial");
    }

    @Test
    void aMountTheEntitySinkRefusesCountsAsLost(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new RefusingEntitySinkAdapter(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        assertEquals(1, losses(session), "an envelope that never came back is a mount that never reached disk");
        assertTrue(session.isPartialSave(0, 0), "so the finish verdict reads partial");
        assertNull(entityChunk(paths, DimensionType.NETHER, mountChunk()), "and the chunk stays empty");
    }

    @Test
    void aMountTheEncodeThrowsOnCountsAsLost(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new ThrowingEntitySinkAdapter(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(),
                "the throw is absorbed so the rest of the finish still runs");

        assertEquals(1, losses(session), "the fail-soft catch loses the mount as surely as the guarded exits");
        assertTrue(session.isPartialSave(0, 0), "so the finish verdict reads partial");
    }

    @Test
    void aFreshDownloadReleasesNothingFromTheLevelDatItIsAboutToReplace(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD,
                DownloadMode.NEW);
        // A level.dat is present because the target folder is being written over. It belongs to a world this
        // download is replacing rather than continuing, so reading a mount out of it would put an entity from
        // some other capture into a save that never held it.
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);
        assertFalse(result.failed(), "the release must not fail the save");
        assertEquals(0, result.entityChunksWritten() + result.entityChunksFailed(),
                "the release submitted no mount to the writer, so a wrong submit cannot hide behind a missing host");

        assertNull(entityChunk(paths, DimensionType.NETHER, mountChunk()),
                "a download that is not a resume carries nothing forward from the folder it overwrites");
        assertEquals(0, losses(session), "and it lost nothing, since it had nothing to release");
        assertFalse(session.isPartialSave(0, 0), "so nothing may stamp it partial");
    }

    @Test
    void aResumeThatFinishedOnTheSameMountReportsNoLoss(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        // Still riding the SAME mount at the finish, so this session's own level.dat carries it and releasing
        // it as a standalone entity would write a second copy of a mount that was never dropped.
        seatedOn(session, mount(), MOUNT);
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);
        assertFalse(result.failed(), "the release must not fail the save");
        assertEquals(0, result.entityChunksWritten() + result.entityChunksFailed(),
                "the release submitted no mount to the writer, so a wrong submit cannot hide behind a missing host");

        assertNull(entityChunk(paths, DimensionType.NETHER, mountChunk()), "the fresh RootVehicle is the mount's copy");
        assertEquals(0, losses(session), "nothing was lost, so nothing may be counted");
        assertFalse(session.isPartialSave(0, 0), "so the finish verdict stays clean");
    }

    @Test
    void aResumeThatFinishedOnAnotherMountStillReleasesThePriorOne(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        // Rode a donkey in the previous download, rode a boat in this one. The fresh record replaces the prior
        // one in the Player slot, so the donkey is preserved by nothing unless it is released here, and being
        // seated on SOMETHING is not the same question as being seated on THAT mount.
        seatedOn(session, otherMount(), OTHER_MOUNT);
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        CompoundTag released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "the mount ridden away from is a world entity and belongs where it was left");
        assertEquals(MOUNT.toString(), soleEntityUuid(released), "and it is the prior mount, not the fresh one");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aPriorMountNestedInsideThisFinishesMountIsNotReleasedAgain(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        // The player is riding the prior mount, but that mount is itself in a boat, so the RootVehicle record
        // holds the OUTER boat with the mount nested under it. The mount is still under the player and still
        // saved by this finish, so releasing it would put a second copy of it in the world.
        seatedOn(session, EntityFixtures.entityCarrying(otherMount(), mount()), OTHER_MOUNT, MOUNT);
        WorldPaths paths = paths(save);
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);
        assertFalse(result.failed(), "the release must not fail the save");
        assertEquals(0, result.entityChunksWritten() + result.entityChunksFailed(),
                "the release submitted no mount to the writer, so a wrong submit cannot hide behind a missing host");

        assertNull(entityChunk(paths, DimensionType.NETHER, mountChunk()),
                "a mount the player is still on must not also be written standalone");
        assertEquals(0, losses(session), "nothing was lost, so nothing may be counted");
        assertFalse(session.isPartialSave(0, 0), "so the finish verdict stays clean");
    }

    @Test
    void aFinishWhosePlayerAssemblyFailedStillReleasesThePriorMount(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        // The capture ran and filled the exclusion set, then the whole player assembly threw, so level.dat
        // takes its no-player arm and carries no RootVehicle at all. The mount is already held back from the
        // standalone write by that same set, so the release is the only thing left that can save it.
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.add(MOUNT);
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        CompoundTag released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "a void-world level.dat leaves the release as the mount's only rescue");
        assertEquals(MOUNT.toString(), soleEntityUuid(released), "and it is the prior mount");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aResumeWhosePriorSessionParkedNoMountReportsNoLoss(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        writePriorPlayerTag(session, save, priorPlayerTag(DimensionType.NETHER, null));
        AsyncSaveWriter writer = saveWriter(paths(save));

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        assertEquals(0, losses(session), "the previous download ended on foot, so it parked no mount");
        assertFalse(session.isPartialSave(0, 0), "so the finish verdict stays clean");
    }

    /** The prior download's mount, carrying the loot whose survival is the whole point of releasing it. */
    private static CompoundTag mount() {
        return EntityFixtures.containerVehicleAt("minecraft:chest_boat", MOUNT, MOUNT_X, MOUNT_Y, MOUNT_Z,
                MOUNT_LOOT);
    }

    /**
     * The chunk the mount's position lands in, stated rather than recomputed with production's own formula, which would
     * agree with whatever formula production used. Not a constant: ChunkPos's class initializer reaches a built-in
     * registry, so touching one before the bootstrap fails the whole class.
     */
    private static ChunkPos mountChunk() {
        return new ChunkPos(-71, 30);
    }

    /**
     * A second mount of the SAME entity type, so identity rests on the UUID alone. Two boats is also the realistic
     * switch; a donkey against a boat would let a check comparing entity types pass by accident.
     */
    private static CompoundTag otherMount() {
        return EntityFixtures.entityAt("minecraft:chest_boat", OTHER_MOUNT, 8.5, 64.0, 8.5);
    }

    /** A prior level.dat player tag: the dimension it finished in, and the mount it was riding if any. */
    private static CompoundTag priorPlayerTag(DimensionType dimension, @Nullable CompoundTag mount) {
        CompoundTag player = new CompoundTag();
        // The band's save keys the player file on its UUID (players/data/<uuid>.dat at 26.x), so a prior written
        // through it must carry one; a client saveWithoutId always does.
        player.putUUID("UUID", PRIOR_PLAYER);
        PlayerTag.setDimension(player, dimension);
        if (mount != null) {
            CompoundTag root = new CompoundTag();
            root.put("Entity", mount);
            player.put("RootVehicle", root);
        }
        return player;
    }

    private static void writePriorLevelDat(LiveCaptureSession session, Path save, DimensionType dimension,
            CompoundTag mount) throws Exception {
        writePriorPlayerTag(session, save, priorPlayerTag(dimension, mount));
    }

    /**
     * Seed a prior download through the band's own save so the production reader finds the player wherever this band
     * writes it (a level.dat Player compound pre-26.x, {@code players/data/<uuid>.dat} at 26.x), then point the session
     * at the level.dat the way world-open does. Hand-writing the level.dat here would encode one band's layout and
     * diverge from the read under test.
     */
    private static void writePriorPlayerTag(LiveCaptureSession session, Path save, CompoundTag player)
            throws Exception {
        LevelDataWriter writer = new LevelDataWriterImpl();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        CapturedPlayer captured = new CapturedPlayer(player, BlockPos.ZERO, 0.0F, 0.0F, DimensionType.OVERWORLD,
                GameType.SURVIVAL, Difficulty.NORMAL);
        LevelStorage storage = new LevelStorageSource(save.getParent(), save.getParent().resolve("backups"),
                DataFixers.getDataFixer()).selectLevel(save.getFileName().toString(), null);
        writer.save(storage, built, captured);
        set(session, "levelDatFile", save.resolve("level.dat"));
    }

    /**
     * Put the session in the state a finish that captured a mount leaves behind: the player tag carrying the
     * RootVehicle record, AND the exclusion set holding every entity of the captured mount tree. Both, because
     * production writes them together and the release reads the set; seeding only the tag would let a check that
     * consults the wrong one pass.
     */
    private static void seatedOn(LiveCaptureSession session, CompoundTag root, UUID... capturedTree)
            throws Exception {
        CompoundTag playerTag = new CompoundTag();
        PlayerTag.setRootVehicle(playerTag, capturedTree[0], root); // the tree's root; nothing here reads Attach
        set(session, "capturedPlayer", new CapturedPlayer(playerTag, BlockPos.ZERO, 0f, 0f, DimensionType.NETHER,
                GameType.SURVIVAL, Difficulty.NORMAL));
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.addAll(ImmutableList.copyOf(capturedTree));
    }

    /**
     * A session bound to no level, finishing in {@code finishedIn}. Entity and container capture are off so the
     * constructor publishes no process-wide capture into the static activation slots, which only {@code finish()}
     * clears; the toggles are asserted rather than assumed, since an unrecognized key falls back to on.
     */
    private static LiveCaptureSession session(VersionAdapter adapter, Path configDirectory,
            DimensionType finishedIn, DownloadMode mode) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(adapter, new HeadlessPlatformBridge(configDirectory), config, null,
                finishedIn, finishedIn, new DownloadTarget("headless", null, mode), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private static LiveCaptureSession resumingSession(VersionAdapter adapter, Path configDirectory,
            DimensionType finishedIn) {
        return session(adapter, configDirectory, finishedIn, DownloadMode.RESUME);
    }

    private static WorldPaths paths(Path save) {
        return new VersionAdapterImpl().worldPaths(save);
    }

    /** A writer over the real region storage, so which dimension a submit routed to is answerable off disk. */
    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                paths::openRegionStorage,
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                new SaveProgress());
    }

    /**
     * Seed a host region chunk at {@code pos} in {@code dimension} (the chunk a prior download had already written).
     */
    private static void seedHost(WorldPaths paths, DimensionType dimension, ChunkPos pos) throws Exception {
        try (IOWorker region = paths.openRegionStorage(dimension)) {
            CompoundTag host = new CompoundTag();
            host.put("Level", new CompoundTag());
            region.store(pos, host).join();
        }
    }

    /**
     * The region chunk at {@code pos} in {@code dimension} whose {@code Level.Entities} the release folded the mount
     * into, or null when nothing was written there (no host chunk exists until the seed above writes one).
     */
    private static @Nullable CompoundTag entityChunk(WorldPaths paths, DimensionType dimension, ChunkPos pos)
            throws Exception {
        try (IOWorker storage = paths.openRegionStorage(dimension)) {
            Optional<CompoundTag> read = Optional.ofNullable(storage.load(pos));
            return read.orElse(null);
        }
    }

    /** The one entity a region chunk's {@code Level.Entities} holds, so both its identity and contents can be read. */
    private static CompoundTag soleEntity(CompoundTag entityChunk) {
        ListTag entities = entityChunk.getCompound("Level").getList("Entities", 10);
        assertEquals(1, entities.size(), "the release writes exactly the one mount");
        return entities.getCompound(0);
    }

    /** The UUID of the one entity a region chunk's {@code Level.Entities} holds, telling a mount from a stray. */
    private static String soleEntityUuid(CompoundTag entityChunk) {
        ListTag entities = entityChunk.getCompound("Level").getList("Entities", 10);
        assertEquals(1, entities.size(), "the release writes exactly the one mount");
        UUID uuid = EntityMerge.readUuid(entities.getCompound(0));
        assertNotNull(uuid, "the written mount keeps the identity the prior tag recorded");
        return uuid.toString();
    }

    /** The item id of the one stack an entity's container holds. */
    private static String soleItemId(CompoundTag entity) {
        ListTag items = entity.getList("Items", 10);
        assertEquals(1, items.size(), "the fixture archives exactly one stack");
        return items.getCompound(0).getString("id");
    }

    private static int losses(LiveCaptureSession session) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField("resumedMountsLost");
        counter.setAccessible(true);
        return counter.getInt(session);
    }

    /** A live capture-state collection, seeded directly because only a client-driven capture fills one. */
    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }

    private static void set(LiveCaptureSession session, String name, Object value) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    /** The production adapter on every axis, so each subclass below swaps exactly the one it is named for. */
    private static class DelegatingAdapter implements VersionAdapter {
        private final VersionAdapterImpl real = new VersionAdapterImpl();

        @Override
        public ChunkCodec chunkCodec() {
            return real.chunkCodec();
        }

        @Override
        public EntitySink entitySink() {
            return real.entitySink();
        }

        @Override
        public ContainerSink containerSink() {
            return real.containerSink();
        }

        @Override
        public LecternSink lecternSink() {
            return real.lecternSink();
        }

        @Override
        public PlayerSink playerSink() {
            return real.playerSink();
        }

        @Override
        public MapSink mapSink() {
            return real.mapSink();
        }

        @Override
        public LevelDataWriter levelDataWriter() {
            return real.levelDataWriter();
        }

        @Override
        public WorldPaths worldPaths(Path saveRoot) {
            return real.worldPaths(saveRoot);
        }
    }

    /** A sink that hands back no envelope, the refusal the release cannot tell from an empty chunk. */
    private static final class RefusingEntitySinkAdapter extends DelegatingAdapter {
        private final EntitySink refusing = new StubEntitySink() {
            @Override
            public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
                return null;
            }
        };

        @Override
        public EntitySink entitySink() {
            return refusing;
        }
    }

    /** A sink whose envelope build throws, the fail-soft catch's own input. */
    private static final class ThrowingEntitySinkAdapter extends DelegatingAdapter {
        private final EntitySink throwing = new StubEntitySink() {
            @Override
            public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
                throw new IllegalStateException("a band entity envelope blew up");
            }
        };

        @Override
        public EntitySink entitySink() {
            return throwing;
        }
    }

    /** The sink members the release never reaches, so a call to one is a routing mistake rather than a pass. */
    private abstract static class StubEntitySink implements EntitySink {
        @Override
        public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos,
                boolean forceMobPersistence) {
            throw new AssertionError("the release encodes an already-serialized tag, never a live entity");
        }

        @Override
        public @Nullable CompoundTag captureRootVehicle(Entity vehicle,
                boolean forceMobPersistence) {
            throw new AssertionError("the release reads the prior tag, it does not capture a live mount");
        }
    }
}
