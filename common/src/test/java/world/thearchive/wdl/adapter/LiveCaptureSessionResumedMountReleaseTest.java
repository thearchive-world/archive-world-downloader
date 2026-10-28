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
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.HorseType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveHandler;
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
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Each exit that reaches the release proper and writes nothing is asserted twice over, on the named counter and on the
 * verdict the completion record stamps from, since a counter no term of the sum reads reports nothing; and the
 * fresh-download, same-mount and nested-mount no-op cases are asserted in the other direction, on the absence of the
 * WRITE rather than the absence of a loss, because a path that wrongly runs and succeeds also loses nothing.
 */
class LiveCaptureSessionResumedMountReleaseTest {
    private static final UUID MOUNT = UUID.fromString("f4c1a8d2-3b76-4e05-9a1c-8d2e6b70f513");
    private static final UUID OTHER_MOUNT = UUID.fromString("0a7e2c95-641b-4d38-8f02-b3d54e18a7c6");
    private static final UUID PRIOR_PLAYER = UUID.fromString("2b9d6f10-84c3-4a57-9e21-7c0f5a3b8d64");
    private static final UUID VILLAGER = UUID.fromString("6d3f9b21-5c8e-4a70-b1d4-9e27c05f83a6");
    private static final String MOUNT_LOOT = "minecraft:diamond";
    private static final int CHEST_SLOT_START = 2;
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

        NBTTagCompound released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "the mount belongs in the dimension the previous download parked it in");
        NBTTagCompound mount = soleEntity(released);
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

        NBTTagCompound released = entityChunk(paths, DimensionType.OVERWORLD, mountChunk());
        assertNotNull(released, "routing by the prior tag must not break the case where the two agree");
        assertEquals(MOUNT.toString(), soleEntityUuid(released), "and it is the mount");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aMountWhoseDimensionThisDownloadCannotRouteCountsAsLost(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.OVERWORLD);
        NBTTagCompound prior = priorPlayerTag(DimensionType.NETHER, mount());
        prior.setString("Dimension", "examplepack:skylands");
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
                chestedDonkey(EntityFixtures.entityWithShortPos("EntityHorse", MOUNT, MOUNT_X, MOUNT_Y)));
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
        seatedOn(session, otherMount(), OTHER_MOUNT);
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        NBTTagCompound released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "the mount ridden away from is a world entity and belongs where it was left");
        assertEquals(MOUNT.toString(), soleEntityUuid(released), "and it is the prior mount, not the fresh one");
        assertFalse(session.isPartialSave(0, 0), "a released mount is not a loss");
    }

    @Test
    void aPriorMountNestedInsideThisFinishesMountIsNotReleasedAgain(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        writePriorLevelDat(session, save, DimensionType.NETHER, mount());
        seatedOn(session, EntityFixtures.entityCarrying(
                EntityFixtures.entityAt("MinecartRideable", OTHER_MOUNT, 8.5, 64.0, 8.5), mount()),
                OTHER_MOUNT, MOUNT);
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
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.add(MOUNT);
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        NBTTagCompound released = entityChunk(paths, DimensionType.NETHER, mountChunk());
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

    /**
     * {@link MerchantOfferScrub} removes an {@code Offers} key without reading its value, so the fixture's
     * {@code Offers} is an empty compound.
     */
    @Test
    void aVillagerLeftAboardTheParkedBoatLosesTheTradesItsClientMadeUp(@TempDir Path temporary) throws Exception {
        Path save = temporary.resolve("save");
        LiveCaptureSession session = resumingSession(new VersionAdapterImpl(), temporary, DimensionType.NETHER);
        NBTTagCompound villager = EntityFixtures.entity("Villager", VILLAGER);
        villager.setTag("Offers", new NBTTagCompound());
        writePriorLevelDat(session, save, DimensionType.NETHER, EntityFixtures.entityCarrying(
                EntityFixtures.entityAt("Boat", MOUNT, MOUNT_X, MOUNT_Y, MOUNT_Z), villager));
        WorldPaths paths = paths(save);
        seedHost(paths, DimensionType.NETHER, mountChunk()); // the prior download already wrote the mount's chunk
        AsyncSaveWriter writer = saveWriter(paths);

        session.releaseResumedDismountedMount(writer);
        assertFalse(writer.finish().get(30, TimeUnit.SECONDS).failed(), "the release must not fail the save");

        NBTTagCompound released = entityChunk(paths, DimensionType.NETHER, mountChunk());
        assertNotNull(released, "a boat shared with a villager is released like any other parked mount");
        NBTTagCompound boat = soleEntity(released);
        assertEquals(MOUNT, EntityMerge.readUuid(boat), "and it is the boat");
        NBTTagCompound passenger = solePassenger(boat);
        assertEquals(VILLAGER, EntityMerge.readUuid(passenger),
                "still carrying the villager that rode with the player");
        assertFalse(passenger.hasKey("Offers"), "which reaches the archive without the trades its client made up");
    }

    private static NBTTagCompound mount() {
        return chestedDonkeyAt(MOUNT, MOUNT_X, MOUNT_Y, MOUNT_Z, MOUNT_LOOT);
    }

    /**
     * A chested donkey at a position, in the shape {@code EntityHorse.writeEntityToNBT} gives those keys:
     * {@code EntityHorse} is the one id the whole horse family shares and {@code Type} tells a donkey from a horse,
     * {@code Items} is read only where {@code ChestedHorse} is set, and the chest's stacks sit at slots from two, past
     * the saddle and armor slots.
     */
    private static NBTTagCompound chestedDonkeyAt(UUID uuid, double x, double y, double z, String... itemIds) {
        return chestedDonkey(EntityFixtures.entityAt("EntityHorse", uuid, x, y, z), itemIds);
    }

    private static NBTTagCompound chestedDonkey(NBTTagCompound horse, String... itemIds) {
        horse.setInteger("Type", HorseType.DONKEY.getOrdinal());
        horse.setBoolean("ChestedHorse", true);
        int[] slots = new int[itemIds.length];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = CHEST_SLOT_START + i;
        }
        horse.setTag("Items", ItemFixtures.itemsAtSlots(slots, itemIds));
        return horse;
    }

    /**
     * The chunk the mount's position lands in, stated rather than computed through production's own locator, which
     * would agree with whatever formula it used.
     */
    private static ChunkPos mountChunk() {
        return new ChunkPos(-71, 30);
    }

    @Test
    void thePriorPlayerRecordBeatsTheEntityChunksWhenBothHoldTheMount(@TempDir Path saves,
            @TempDir Path configDirectory) throws Exception {
        Path save = saves.resolve("world");
        LiveCaptureSession session = session(new VersionAdapterImpl(), configDirectory, DimensionType.NETHER,
                DownloadMode.RESUME);
        writePriorLevelDat(session, save, DimensionType.NETHER,
                chestedDonkeyAt(MOUNT, MOUNT_X, MOUNT_Y, MOUNT_Z, "minecraft:diamond", "minecraft:emerald"));
        bankRecoveredContents(session, MOUNT, "minecraft:diamond");

        NBTTagCompound raw = seatedRawWithEmptyMount();
        session.restoreSeatedMountContents(raw);

        assertEquals(2, mountItems(raw).tagCount(),
                "the player record is the fresher source for a ridden mount, so filling from the entity chunks "
                        + "ahead of it would destroy what it alone still holds");
    }

    @Test
    void theEntityChunksFillWhatThePriorRecordCannotReach(@TempDir Path saves, @TempDir Path configDirectory)
            throws Exception {
        Path save = saves.resolve("world");
        LiveCaptureSession session = session(new VersionAdapterImpl(), configDirectory, DimensionType.NETHER,
                DownloadMode.RESUME);
        writePriorLevelDat(session, save, DimensionType.NETHER, null);
        bankRecoveredContents(session, MOUNT, "minecraft:diamond");

        NBTTagCompound raw = seatedRawWithEmptyMount();
        session.restoreSeatedMountContents(raw);

        assertEquals(1, mountItems(raw).tagCount(),
                "and where that record holds nothing, the entity chunks are the only source there is");
    }

    /**
     * The fresh seated record as a live client serializes it: the mount reads chested with an empty chest list, because
     * the client never resizes the two-slot chest it builds every mount with when the chested flag arrives.
     */
    private static NBTTagCompound seatedRawWithEmptyMount() {
        NBTTagCompound raw = new NBTTagCompound();
        PlayerTag.setRootVehicle(raw, MOUNT, chestedDonkeyAt(MOUNT, MOUNT_X, MOUNT_Y, MOUNT_Z));
        return raw;
    }

    private static NBTTagList mountItems(NBTTagCompound raw) {
        return raw.getCompoundTag("RootVehicle").getCompoundTag("Entity").getTagList("Items", 10);
    }

    private static void bankRecoveredContents(LiveCaptureSession session, UUID uuid, String... itemIds)
            throws Exception {
        RecoveredEntityContent bank = state(session, "recoveredEntityContent");
        NBTTagCompound chunk = EntityFixtures.entityChunkTagWith(
                chestedDonkeyAt(uuid, MOUNT_X, MOUNT_Y, MOUNT_Z, itemIds));
        // The scan hands the bank the region chunk, whose entities sit under Level.Entities at this band.
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("Entities", chunk.getTagList("Entities", 10));
        NBTTagCompound host = new NBTTagCompound();
        host.setTag("Level", level);
        bank.record(host);
    }

    private static NBTTagCompound otherMount() {
        return chestedDonkeyAt(OTHER_MOUNT, 8.5, 64.0, 8.5);
    }

    private static NBTTagCompound priorPlayerTag(DimensionType dimension, @Nullable NBTTagCompound mount) {
        NBTTagCompound player = new NBTTagCompound();
        player.setUniqueId("UUID", PRIOR_PLAYER);
        PlayerTag.setDimension(player, dimension);
        if (mount != null) {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("Entity", mount);
            player.setTag("RootVehicle", root);
        }
        return player;
    }

    private static void writePriorLevelDat(LiveCaptureSession session, Path save, DimensionType dimension,
            NBTTagCompound mount) throws Exception {
        writePriorPlayerTag(session, save, priorPlayerTag(dimension, mount));
    }

    /**
     * Hand-writing the level.dat here would encode one band's layout and diverge from the read under test.
     */
    private static void writePriorPlayerTag(LiveCaptureSession session, Path save, NBTTagCompound player)
            throws Exception {
        LevelDataWriter writer = new LevelDataWriterImpl();
        LevelDataWriter.LevelData built = writer.buildLevelData(WorldOutputConfig.DEFAULTS, null);
        CapturedPlayer captured = new CapturedPlayer(player, BlockPos.ORIGIN, 0.0F, 0.0F, DimensionType.OVERWORLD,
                GameType.SURVIVAL, EnumDifficulty.NORMAL);
        ISaveHandler storage = new AnvilSaveConverter(save.getParent().toFile(), null)
                .getSaveLoader(save.getFileName().toString(), true);
        writer.save(storage, built, captured);
        set(session, "levelDatFile", save.resolve("level.dat"));
    }

    private static void seatedOn(LiveCaptureSession session, NBTTagCompound root, UUID... capturedTree)
            throws Exception {
        NBTTagCompound playerTag = new NBTTagCompound();
        PlayerTag.setRootVehicle(playerTag, capturedTree[0], root);
        set(session, "capturedPlayer", new CapturedPlayer(playerTag, BlockPos.ORIGIN, 0f, 0f, DimensionType.NETHER,
                GameType.SURVIVAL, EnumDifficulty.NORMAL));
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.addAll(ImmutableList.copyOf(capturedTree));
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots, which only the finish path clears; the toggles are asserted rather than assumed, since an
     * unrecognized key falls back to on.
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

    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                paths::openRegionStorage,
                () -> {},
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                new SaveProgress());
    }

    private static void seedHost(WorldPaths paths, DimensionType dimension, ChunkPos pos) throws Exception {
        try (WdlRegionStorage region = paths.openRegionStorage(dimension)) {
            NBTTagCompound host = new NBTTagCompound();
            host.setTag("Level", new NBTTagCompound());
            region.write(pos, host);
        }
    }

    private static @Nullable NBTTagCompound entityChunk(WorldPaths paths, DimensionType dimension, ChunkPos pos)
            throws Exception {
        try (WdlRegionStorage storage = paths.openRegionStorage(dimension)) {
            Optional<NBTTagCompound> read = Optional.ofNullable(storage.read(pos));
            return read.orElse(null);
        }
    }

    private static NBTTagCompound soleEntity(NBTTagCompound entityChunk) {
        NBTTagList entities = entityChunk.getCompoundTag("Level").getTagList("Entities", 10);
        assertEquals(1, entities.tagCount(), "the release writes exactly the one mount");
        return entities.getCompoundTagAt(0);
    }

    private static String soleEntityUuid(NBTTagCompound entityChunk) {
        NBTTagList entities = entityChunk.getCompoundTag("Level").getTagList("Entities", 10);
        assertEquals(1, entities.tagCount(), "the release writes exactly the one mount");
        UUID uuid = EntityMerge.readUuid(entities.getCompoundTagAt(0));
        assertNotNull(uuid, "the written mount keeps the identity the prior tag recorded");
        return uuid.toString();
    }

    private static NBTTagCompound solePassenger(NBTTagCompound entity) {
        assertTrue(entity.hasKey("Passengers", 9), "the released mount carries a Passengers list");
        NBTTagList passengers = entity.getTagList("Passengers", 10);
        assertEquals(1, passengers.tagCount(), "the fixture seats exactly one passenger");
        return passengers.getCompoundTagAt(0);
    }

    private static String soleItemId(NBTTagCompound entity) {
        NBTTagList items = entity.getTagList("Items", 10);
        assertEquals(1, items.tagCount(), "the fixture archives exactly one stack");
        return items.getCompoundTagAt(0).getString("id");
    }

    private static int losses(LiveCaptureSession session) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField("resumedMountsLost");
        counter.setAccessible(true);
        return counter.getInt(session);
    }

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

    private static final class RefusingEntitySinkAdapter extends DelegatingAdapter {
        private final EntitySink refusing = new StubEntitySink() {
            @Override
            public @Nullable NBTTagCompound encodeChunk(List<NBTTagCompound> entityTags, ChunkPos pos) {
                return null;
            }
        };

        @Override
        public EntitySink entitySink() {
            return refusing;
        }
    }

    private static final class ThrowingEntitySinkAdapter extends DelegatingAdapter {
        private final EntitySink throwing = new StubEntitySink() {
            @Override
            public @Nullable NBTTagCompound encodeChunk(List<NBTTagCompound> entityTags, ChunkPos pos) {
                throw new IllegalStateException("a band entity envelope blew up");
            }
        };

        @Override
        public EntitySink entitySink() {
            return throwing;
        }
    }

    private abstract static class StubEntitySink implements EntitySink {
        @Override
        public @Nullable NBTTagCompound encodeChunk(List<Entity> entities, ChunkPos pos,
                boolean forceMobPersistence) {
            throw new AssertionError("the release encodes an already-serialized tag, never a live entity");
        }

        @Override
        public @Nullable NBTTagCompound captureRootVehicle(Entity vehicle,
                boolean forceMobPersistence) {
            throw new AssertionError("the release reads the prior tag, it does not capture a live mount");
        }
    }
}
