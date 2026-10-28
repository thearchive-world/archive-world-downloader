// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPosOrNull;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderOf;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderReferencing;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SerializableUUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
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
import world.thearchive.wdl.core.SendRangeSampler;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * Neither half of the wiring alone is enough: the named counter moved (the verdict sums every term, so a site rewired
 * to a sibling counter still reads partial), and the verdict turned partial (a counter no term of the sum reads is a
 * loss the verdict does not report).
 */
class LiveCaptureSessionLossTallyTest {
    private static final UUID VEHICLE = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");
    private static final UUID FRAME = UUID.fromString("2f9c4b18-7d60-4a35-9e21-0c7b6d5a3e14");

    private static RegistryAccess.Frozen registries;
    private final ContainerSink sink = new ContainerSinkImpl();
    private final ChunkPos chunk = new ChunkPos(0, 0);
    // The holders' x and z differ so that an axis confusion in a block-entity locator misses the match rather than
    // landing on it, and a no-match is counted as neither merged nor failed, which would read as a clean download.
    private final BlockPos chest = new BlockPos(2, 64, 13);

    private final BlockPos lectern = new BlockPos(9, 64, 4);

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /**
     * Entity and container capture are off so the constructor publishes no process-wide capture into the static
     * activation slots; a test that turns either on leaves a session published there, since only the finish path clears
     * it. The toggles are asserted rather than assumed: an unrecognized config key falls back to the default, which is
     * on for both, so a silent key rename would arm every session this fixture builds.
     */
    private static LiveCaptureSession session(VersionAdapter adapter, Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        return new LiveCaptureSession(adapter, new HeadlessPlatformBridge(configDirectory), config, null,
                Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW),
                new SavedChunkIndex(), new CoveredChunkIndex(), new SendRangeEstimator(), false, false,
                BobbyChunkFilter.INACTIVE, () -> {});
    }

    private static LiveCaptureSession voidSkippingSession(Path configDirectory) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("skipVoidChunks", "true");
        WdlConfig config = WdlConfig.parse(properties);
        assertFalse(config.captureEntities(), "the fixture must not publish an entity capture");
        assertFalse(config.captureContainers(), "the fixture must not publish an interaction capture");
        assertTrue(config.worldOutput().skipVoidChunks(), "the void skip is the whole subject, so it must be on");
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, Level.OVERWORLD, Level.OVERWORLD, TestRegistries.frozen(),
                new DownloadTarget("headless", null, DownloadMode.NEW), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private ChunkSnapshotSource voidChunk() {
        return SyntheticChunks.withBlockAt(registries, new BlockPos(0, 64, 0), Blocks.AIR.defaultBlockState());
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

    private static MapArchive throwingArchive() {
        return new MapArchive(MapManifest.empty(), id -> {
            throw new IllegalStateException("the map image could not be read");
        }, (archiveId, dataTag) -> {});
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

    private static final class ThrowingMergeAdapter extends DelegatingAdapter {
        private final ContainerSink throwingSink = new ContainerSink() {
            private final ContainerSink real = new ContainerSinkImpl();

            @Override
            public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess itemRegistries) {
                return real.captureItems(items, itemRegistries);
            }

            @Override
            public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
                throw new IllegalStateException("a band container merge blew up");
            }
        };

        @Override
        public ContainerSink containerSink() {
            return throwingSink;
        }
    }

    private static final class ThrowingLecternMergeAdapter extends DelegatingAdapter {
        private final LecternSink throwingSink = new LecternSink() {
            @Override
            public CompoundTag captureBook(ItemStack book, int page, RegistryAccess bookRegistries) {
                throw new AssertionError("the fold path captures no book");
            }

            @Override
            public CompoundTag merge(CompoundTag lecternBlockEntityTag, CompoundTag capturedBookHolder) {
                throw new IllegalStateException("a band lectern merge blew up");
            }
        };

        @Override
        public LecternSink lecternSink() {
            return throwingSink;
        }
    }

    private static final class NullEnvelopeAdapter extends DelegatingAdapter {
        private final EntitySink nullEnvelopeSink = new EntitySink() {
            @Override
            public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos,
                    RegistryAccess entityRegistries, boolean forceMobPersistence) {
                throw new AssertionError("a headless drain has no live entity to serialize");
            }

            @Override
            public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
                return null;
            }

            @Override
            public @Nullable CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess vehicleRegistries,
                    boolean forceMobPersistence) {
                throw new AssertionError("a headless drain has no ridden vehicle to serialize");
            }
        };

        @Override
        public EntitySink entitySink() {
            return nullEnvelopeSink;
        }
    }

    private static final class ThrowingEntityEncodeAdapter extends DelegatingAdapter {
        private final EntitySink throwingSink = new EntitySink() {
            @Override
            public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos,
                    RegistryAccess entityRegistries, boolean forceMobPersistence) {
                throw new IllegalStateException("the vanilla save codec rejected this entity");
            }

            @Override
            public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
                throw new AssertionError("the single-entity encode never reaches the tag envelope arm");
            }

            @Override
            public @Nullable CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess vehicleRegistries,
                    boolean forceMobPersistence) {
                throw new AssertionError("this fixture drives no ridden vehicle");
            }
        };

        @Override
        public EntitySink entitySink() {
            return throwingSink;
        }
    }

    private static final class RefusingEntityEncodeAdapter extends DelegatingAdapter {
        private final EntitySink refusingSink = new EntitySink() {
            @Override
            public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos,
                    RegistryAccess entityRegistries, boolean forceMobPersistence) {
                return null;
            }

            @Override
            public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
                throw new AssertionError("the single-entity encode never reaches the tag envelope arm");
            }

            @Override
            public @Nullable CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess vehicleRegistries,
                    boolean forceMobPersistence) {
                throw new AssertionError("this fixture drives no ridden vehicle");
            }
        };

        @Override
        public EntitySink entitySink() {
            return refusingSink;
        }
    }

    private static final class BareEntity extends Entity {
        private BareEntity() {
            super(EntityType.PIG, null);
        }

        @Override
        public Packet<?> getAddEntityPacket() {
            return new ClientboundAddEntityPacket(this);
        }

        @Override
        protected void defineSynchedData() {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return false;
        }
    }

    private ChunkSnapshotSource chestChunk() {
        return SyntheticChunks.fullWithBlockEntities(registries, true,
                List.of(blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ()),
                        blockEntity("minecraft:lectern", lectern.getX(), lectern.getY(), lectern.getZ())));
    }

    private static @Nullable CompoundTag blockEntityOnDisk(WorldPaths paths, ChunkPos pos, BlockPos at)
            throws Exception {
        try (IOWorker storage = paths.openRegionStorage(Level.OVERWORLD)) {
            CompoundTag chunkTag = Optional.ofNullable(storage.load(pos))
                    .orElseThrow(() -> new AssertionError("chunk not on disk"));
            return findByPosOrNull(chunkTag, at.getX(), at.getY(), at.getZ());
        }
    }

    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items, registries);
    }

    private static CompoundTag entity(UUID uuid) {
        CompoundTag entity = new CompoundTag();
        entity.put("UUID", SerializableUUID.CODEC.encodeStart(NbtOps.INSTANCE, uuid).getOrThrow(false, s -> {}));
        return entity;
    }

    private CompoundTag entityDisplaying(UUID uuid, ItemStack stack) {
        CompoundTag tag = entity(uuid);
        tag.put("Item", (CompoundTag) ((ListTag) holderOf(sink, registries, stack).get("Items")).get(0));
        return tag;
    }

    @Test
    void everyDimensionScopedStoreIsBoundToTheStartingDimensionsInstance(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);

        assertBoundToStartingDimension(session, "allCaptured", "capturedByDimension");
        assertBoundToStartingDimension(session, "capturedBlockKeys", "capturedBlockKeysByDimension");
        assertBoundToStartingDimension(session, "replacedBlockKeys", "replacedBlockKeysByDimension");
        assertBoundToStartingDimension(session, "bookshelfSlots", "bookshelfSlotsByDimension");
        assertBoundToStartingDimension(session, "capturedBlockTypes", "capturedBlockTypesByDimension");
    }

    /**
     * Identity rather than equality, for the drift a deleted bind or swap does not produce: those leave the map with no
     * entry at all, which equality catches too, but a swap that assigned a copy of the map's instance would leave two
     * empty collections that compare equal. The non-null check is what keeps this from passing on two nulls.
     */
    private static void assertBoundToStartingDimension(LiveCaptureSession session, String store,
            String byDimension) throws Exception {
        Map<ResourceKey<Level>, ?> perDimension = state(session, byDimension);
        Object bound = perDimension.get(Level.OVERWORLD);
        assertNotNull(bound, byDimension + " must hold an instance for the bound dimension");
        assertSame(bound, state(session, store),
                store + " must be the instance " + byDimension + " holds for the bound dimension, or everything"
                        + " the download records in it is invisible to every reader of that map");
    }

    @Test
    void aHolderMapRemapLostInTheChunkDrainLandsInTheRemapTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, throwingArchive(), () -> saveWriter(paths));
        captureChunk(session, chunk, chestChunk());
        stashContainer(session, chest, holderReferencing(sink, registries, 5));

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.chunksWritten(),
                "an unremappable map in a chest is a per-holder loss, so the chunk itself still reached disk");
        assertEquals(1, losses(session, "mapsRemapFailed"),
                "the drain-time holder remap failed, so the map that renders blank is counted as one");
        assertEquals(0, losses(session, "blockContainersFailed"),
                "no merge threw, so nothing lands in the tally beside it");
        assertTrue(session.isPartialSave(0, 0), "and one blank map is enough to make the finish partial");
    }

    @Test
    void aFramedMapRemapLostInTheEntityDrainLandsInTheRemapTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, throwingArchive(), () -> saveWriter(paths));
        bufferEntity(session, FRAME, chunk, entityDisplaying(FRAME, filledMap(9)));

        session.flushEntityChunk(writer, chunk);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.entityChunksWritten(),
                "an unremappable framed map is a per-item loss, so the whole entity-chunk still reached disk");
        assertEquals(1, losses(session, "mapsRemapFailed"),
                "the entity path's remap failed, so the map that frame renders blank is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and one blank map is enough to make the finish partial");
    }

    private CompoundTag merchantHolderSellingMap(int mapId) {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemStack(Items.EMERALD, 1), filledMap(mapId), 1, 0, 0.0f));
        return MerchantOfferCapture.serialize(offers, 0, false);
    }

    @Test
    void aVillagerTradeMapRemapLostInThePrepareLandsInTheRemapTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");
        AsyncSaveWriter writer = session.bindWorldOpen(paths, throwingArchive(), () -> saveWriter(paths));
        Map<UUID, CompoundTag> merchantStash = state(session, "merchantStash");
        merchantStash.put(VEHICLE, merchantHolderSellingMap(9));
        bufferEntity(session, VEHICLE, chunk, entity(VEHICLE));

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.entityChunksWritten(),
                "an unremappable trade map is a per-holder loss, so the villager still reached disk");
        assertEquals(1, losses(session, "mapsRemapFailed"),
                "the prepare's offer remap failed, so the trade map that renders blank is counted as one");
        assertEquals(0, losses(session, "villagerTradesLost"),
                "the villager was written and its trades folded, so nothing is an orphan");
        assertTrue(session.isPartialSave(0, 0), "and one blank trade map is enough to make the finish partial");
    }

    @Test
    void anUnwrittenVillagersStashedTradesLandInTheOrphanTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        Map<UUID, CompoundTag> merchantStash = state(session, "merchantStash");
        merchantStash.put(VEHICLE, merchantHolderSellingMap(9));
        assertEquals(0, losses(session, "villagerTradesLost"), "nothing has been counted before the orphan report");

        session.countOrphanedMerchantTrades();

        assertEquals(1, losses(session, "villagerTradesLost"),
                "the villager was stashed but never written as a top-level entity, so its trades are an orphan loss");
    }

    @Test
    void anUnwrittenVehiclesStashedContentsLandInTheOrphanTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        assertEquals(0, losses(session, "containerVehiclesLost"), "nothing has been counted before the orphan report");

        session.countOrphanedContainerVehicles();

        assertEquals(1, losses(session, "containerVehiclesLost"),
                "the vehicle was stashed but never written as a top-level entity, so its contents are an orphan loss");
    }

    @Test
    void aRiddenMountsLostContainerFoldLandsInTheEntityContainerTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new ThrowingMergeAdapter(), temporary);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        assertFalse(session.isPartialSave(0, 0), "nothing has folded, so the finish reads clean");

        session.foldRidingVehicleContents(entity(VEHICLE));

        assertEquals(1, losses(session, "entityContainersFailed"),
                "the mount saves without the contents the player opened, so the loss is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void aRiddenMountsFoldedContentsReachTheTagItReturns(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));

        CompoundTag folded = session.foldRidingVehicleContents(entity(VEHICLE));

        assertNotNull(folded.get("Items"), "the contents the player opened are folded into the tag the mount saves as");
        assertEquals(1, ((ListTag) folded.get("Items")).size(), "carrying the one stack that was stashed");
        assertEquals(0, losses(session, "entityContainersFailed"), "and a fold that worked counts no loss");
        assertFalse(session.isPartialSave(0, 0), "so the finish reads clean");
    }

    @Test
    void anEntityChunksLostContainerFoldLandsInTheEntityContainerTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new ThrowingMergeAdapter(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        bufferEntity(session, VEHICLE, chunk, entity(VEHICLE));
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.flushEntityChunk(writer, chunk);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.entityChunksWritten(),
                "a failed fold loses the contents, not the vehicle, so the entity-chunk still reached disk");
        assertEquals(1, losses(session, "entityContainersFailed"),
                "the vehicle saves without the contents the player opened, so the loss is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void aBufferedChunksLostContainerFoldLandsInTheBlockContainerTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new ThrowingMergeAdapter(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        captureChunk(session, chunk, chestChunk());
        stashContainer(session, chest, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.chunksWritten(),
                "a failed fold loses the chest's contents, not the chunk, so the chunk still reached disk");
        assertNotNull(blockEntityOnDisk(paths, chunk, chest),
                "with its chest still standing: a chunk written emptied would lose far more than the fold did");
        assertEquals(1, losses(session, "blockContainersFailed"),
                "the chest saves empty, so the contents the player opened are counted lost");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void aBufferedChunksLostLecternFoldLandsInTheBlockContainerTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new ThrowingLecternMergeAdapter(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        captureChunk(session, chunk, chestChunk());
        stashLectern(session, lectern, new CompoundTag());
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.chunksWritten(), "a failed book fold loses the book, not the chunk");
        assertEquals(1, losses(session, "blockContainersFailed"),
                "the lectern saves without its book, so the loss lands in the same tally the chest fold feeds");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void anOrphanSweepsLostContainerFoldLandsInTheBlockContainerTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new ThrowingMergeAdapter(), temporary);
        WorldPaths paths = paths(temporary.resolve("save"));
        flushPriorChunk(paths);
        AsyncSaveWriter writer = saveWriter(paths);
        // No captured chunk, so the whole-buffer drain finds nothing to flush and the stashed holder is residual:
        // the backtrack-and-open case, whose contents reach disk only through the sweep's read-modify-write.
        stashContainer(session, chest, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        assertFalse(session.isPartialSave(0, 0), "nothing has swept, so the finish reads clean");

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "a failed fold loses the chest's contents, not the on-disk chunk");
        assertEquals(1, losses(session, "blockContainersFailed"),
                "the on-disk chest keeps saving empty, so the contents the player opened are counted lost");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void anEntityChunkLostAtFlushReachesTheVerdictThroughTheStructuralLossTally(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new NullEnvelopeAdapter(), temporary);
        installPacketCapture(session);
        // Two entities, so the count is the entities lost rather than the chunks they were lost in, and a
        // literal 1 in place of the reconciliation's own figure cannot pass.
        bufferEntity(session, VEHICLE, chunk, entity(VEHICLE));
        bufferEntity(session, FRAME, chunk, entity(FRAME));
        AsyncSaveWriter writer = saveWriter(paths(temporary.resolve("save")));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.flushEntityChunk(writer, chunk);
        session.reportEntityReconciliation();
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertEquals(0, result.entityChunksWritten(), "the refused envelope submitted nothing to write");
        assertEquals(2, losses(session, "structuralEntitiesLost"),
                "both entities dropped with their chunk are counted a structural loss, not reload churn");
        assertTrue(session.isPartialSave(0, 0), "and that makes the finish partial rather than clean");
    }

    @Test
    void aChunkWhoseSnapshotThrowsLandsInTheChunkCaptureTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        assertFalse(session.isPartialSave(0, 0), "nothing has been captured, so the finish reads clean");

        session.recordChunkCaptureLoss(chunk, new IllegalStateException("the chunk snapshot blew up"));

        assertEquals(1, losses(session, "chunksCaptureFailed"),
                "the terrain never reached the buffer, so the chunk missing from the save is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and one missing chunk is enough to make the finish partial");
    }

    @Test
    void aChunkThatKeepsThrowingIsCountedOncePerPosition(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        ChunkPos other = new ChunkPos(4, 7);

        for (int tick = 0; tick < 5; tick++) {
            session.recordChunkCaptureLoss(chunk, new IllegalStateException("the chunk snapshot blew up"));
        }
        session.recordChunkCaptureLoss(other, new IllegalStateException("the chunk snapshot blew up"));

        assertEquals(2, losses(session, "chunksCaptureFailed"),
                "five retries of one position and one of another are two lost chunks, not six");
    }

    @Test
    void anEntityWhoseEncodeThrowsReachesTheVerdictThroughTheStructuralLossTally(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new ThrowingEntityEncodeAdapter(), temporary);
        installPacketCapture(session);
        assertFalse(session.isPartialSave(0, 0), "nothing has been encoded, so the finish reads clean");

        assertNull(session.encodeSingleEntity(new BareEntity(), chunk, LiveCaptureSession.EntitySource.RECONSTRUCTED),
                "a throwing encode degrades to no tag rather than aborting the drain around it");
        session.reportEntityReconciliation();

        assertEquals(1, losses(session, "reconstructEncodeFailures"),
                "the entity the sink would have written is gone, so the loss is counted as one");
        assertEquals(1, losses(session, "structuralEntitiesLost"),
                "and it is structural, the arm of the reconciliation the partial verdict reads");
        assertTrue(session.isPartialSave(0, 0), "so the finish is partial rather than clean");
    }

    @Test
    void anEntityWhoseReconstructThrowsIsSkippedPerEntityRatherThanAbortingTheDrainedChunk(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        ChunkPos laterChunk = new ChunkPos(1, 0);
        UUID laterEntity = UUID.fromString("7a3e1c92-4f88-4d21-8b0a-6c5e9f2d1a34");
        captureTerrain(session, chunk);
        captureTerrain(session, laterChunk);
        spawn(capture, 1, VEHICLE, chunk);
        spawn(capture, 2, FRAME, chunk);
        spawn(capture, 3, laterEntity, laterChunk);
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        try {
            session.promotePacketEntities(LiveCaptureSession.PromotePass.FINISH, 0, 0, 0);
        } catch (RuntimeException escaped) {
            // a reconstruct throw that escaped the pass lands here, taking its drained siblings with it
        }
        session.reportEntityReconciliation();

        assertEquals(3, losses(session, "createDrops"),
                "each entity whose reconstruct threw is skipped and counted on its own, so one bad entity takes "
                        + "neither its drained-chunk siblings nor the pass's later chunks down with it");
        assertEquals(3, losses(session, "structuralEntitiesLost"),
                "and they are structural, the arm of the reconciliation the partial verdict reads");
        assertTrue(session.isPartialSave(0, 0), "so the finish is partial rather than reporting the save clean");
    }

    /**
     * Two entities, so a catch hoisted out of the per-entity loop, which would destroy every refusal behind the first,
     * cannot pass as one loss.
     */
    @Test
    void anEntityTheRetryThrowsOnReachesTheVerdictThroughTheStructuralLossTally(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        installPacketCapture(session);
        // The level-free fixture throws out of level(), the retry's first hop.
        Set<UUID> refused = state(session, "primeRefusedEntities");
        refused.add(VEHICLE);
        refused.add(FRAME);
        assertFalse(session.isPartialSave(0, 0), "nothing has been retried, so the finish reads clean");

        session.retryRefusedPrimes();
        session.reportEntityReconciliation();

        assertEquals(2, losses(session, "primeEncodeFailures"),
                "both mounts the re-offer could not reach are gone from the save, and both are counted");
        assertEquals(2, losses(session, "structuralEntitiesLost"),
                "and they are structural, the arm of the reconciliation the partial verdict reads");
        assertTrue(session.isPartialSave(0, 0), "so the finish is partial rather than reporting the save clean");
    }

    /**
     * The guard against a bare counter placed at the top of the loop body or in a finally, either of which would turn
     * every download that recovers a refused mount partial.
     */
    @Test
    void aRetryThrowOnAnEntityAlreadyBoundForDiskCountsNoLoss(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        installPacketCapture(session);
        Set<UUID> saved = state(session, "savedEntities");
        saved.add(VEHICLE);
        Set<UUID> refused = state(session, "primeRefusedEntities");
        refused.add(VEHICLE);

        session.retryRefusedPrimes();
        session.reportEntityReconciliation();

        assertEquals(0, losses(session, "primeEncodeFailures"), "the entity reaches disk, so the throw counts nothing");
        assertFalse(session.isPartialSave(0, 0), "and the download that saved it reports clean");
    }

    @Test
    void anEntityTheSinkRefusesIsReportedWithoutTouchingTheVerdict(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new RefusingEntityEncodeAdapter(), temporary);
        installPacketCapture(session);

        assertNull(session.encodeSingleEntity(new BareEntity(), chunk, LiveCaptureSession.EntitySource.PRIMED),
                "a refused entity has no tag either, which is exactly why the two need separate counters");
        session.reportEntityReconciliation();

        assertEquals(1, losses(session, "primeSinkSkips"), "the refusal is reported so the drop stays visible");
        assertEquals(0, losses(session, "primeEncodeFailures"), "but it is not charged to the loss counter");
        assertEquals(0, losses(session, "structuralEntitiesLost"), "nor to the structural count the verdict reads");
        assertFalse(session.isPartialSave(0, 0), "so a passenger saved nested leaves the finish clean");
    }

    @Test
    void framesLeftByAnAbortedFinishDrainReachTheVerdictThroughTheStructuralLossTally(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        captureTerrain(session, chunk);
        spawn(capture, 1, VEHICLE, chunk);
        spawn(capture, 2, FRAME, chunk);
        spawn(capture, 3, UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100"), new ChunkPos(30, 30));
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.countAbandonedFrames();
        session.reportEntityReconciliation();

        assertEquals(2, losses(session, "drainAbortDrops"),
                "the two received entities in captured terrain are lost, and counted as the two they are");
        assertEquals(1, losses(session, "droppedUncaptured"),
                "the third was never savable, so it stays the ordinary privacy-gate drop rather than a loss");
        assertEquals(2, losses(session, "structuralEntitiesLost"),
                "the loss half is structural, the arm of the reconciliation the partial verdict reads");
        assertTrue(session.isPartialSave(0, 0), "so the finish is partial rather than clean");
    }

    /**
     * The invariant the abandoned-frame sweep has to run after the flush to preserve: a chunk carrying nothing but
     * entities the accumulator still holds is not void, because the position it would drop is the privacy gate those
     * entities are written through. Emptying the accumulator ahead of the flush would turn this chunk into a void one
     * and lose its terrain as well, which is a terrain loss the entity failure had not itself caused.
     */
    @Test
    void aChunkHoldingOnlyAccumulatedEntitiesSurvivesTheVoidSkip(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = voidSkippingSession(temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        captureChunk(session, chunk, voidChunk());
        captureTerrain(session, chunk);
        spawn(capture, 1, VEHICLE, chunk);

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(1, result.chunksWritten(),
                "an entity still held for this chunk is a reason to keep it, so its terrain reaches disk");
    }

    @Test
    void theSameChunkWithNothingHeldForItIsDroppedAsVoid(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = voidSkippingSession(temporary);
        installPacketCapture(session);
        WorldPaths paths = paths(temporary.resolve("save"));
        AsyncSaveWriter writer = saveWriter(paths);
        captureChunk(session, chunk, voidChunk());
        captureTerrain(session, chunk);

        session.flushBuffer(writer, true, 0, 0, 0);
        AsyncSaveWriter.SaveResult result = writer.finish().get(30, TimeUnit.SECONDS);

        assertFalse(result.failed(), "the drain hit no hard error, so the save was not aborted");
        assertEquals(0, result.chunksWritten(),
                "the fixture really is a void chunk, so with nothing held for it the user's setting drops it");
    }

    @Test
    void aMountCapturedIntoTheRootVehicleIsNotCountedAbandoned(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        captureTerrain(session, chunk);
        spawn(capture, 1, VEHICLE, chunk);
        spawn(capture, 2, FRAME, chunk);
        excludeFromStandaloneWrite(session, VEHICLE);

        session.countAbandonedFrames();

        assertEquals(1, losses(session, "drainAbortDrops"),
                "only the frame nothing else saved is abandoned; the mount is in the player's RootVehicle");
    }

    /**
     * The chunk is captured in the other dimension and not in the bound one, so the unbound-dimension sweep would move
     * its counts if it tested the key without its dimension.
     */
    @Test
    void aFrameHeldForAnotherDimensionOnCapturedTerrainReachesTheVerdict(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        captureTerrain(session, Level.NETHER, chunk);
        capture.enterDimension(Level.NETHER.location().toString());
        spawn(capture, 1, VEHICLE, chunk);
        spawn(capture, 2, FRAME, chunk);
        assertFalse(session.isPartialSave(0, 0), "nothing has drained, so the finish reads clean");

        session.countAbandonedFrames();
        session.countUnboundDimensionFrames();
        session.reportEntityReconciliation();

        assertEquals(0, losses(session, "drainAbortDrops"),
                "an entity of another world is not this dimension's abandoned frame, whatever its chunk key");
        assertEquals(0, losses(session, "droppedUncaptured"),
                "nor a gate refusal, because the gate in ITS dimension had captured the terrain under it");
        assertEquals(2, losses(session, "unboundDimensionDrops"),
                "they were writable and nothing wrote them, counted as the two they are");
        assertEquals(2, losses(session, "structuralEntitiesLost"),
                "through the structural count, the arm of the reconciliation the partial verdict reads");
        assertTrue(session.isPartialSave(0, 0), "so the finish is partial rather than clean");
    }

    @Test
    void aFrameHeldForAnotherDimensionOnUncapturedTerrainStaysBenign(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        capture.enterDimension(Level.NETHER.location().toString());
        spawn(capture, 1, VEHICLE, chunk);
        spawn(capture, 2, FRAME, chunk);

        session.countUnboundDimensionFrames();
        session.reportEntityReconciliation();

        assertEquals(2, losses(session, "droppedUncaptured"),
                "the gate refused them in their own dimension, which is the benign outcome it exists for");
        assertEquals(0, losses(session, "unboundDimensionDrops"), "so nothing is charged as unwritten");
        assertEquals(0, losses(session, "structuralEntitiesLost"), "nor to the structural count");
        assertFalse(session.isPartialSave(0, 0), "and a routine portal trip leaves the finish clean");
    }

    @Test
    void theDimensionChangeDrainHoldsWhatTheGateRefusesAndOnlyTheFinishDropsIt(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        String bound = Level.OVERWORLD.location().toString();
        spawn(capture, 1, VEHICLE, chunk);

        session.promotePacketEntities(LiveCaptureSession.PromotePass.LEAVING_DIMENSION, 0, 0, 0);

        assertEquals(0, losses(session, "droppedUncaptured"),
                "a portal is not the last pass, so the refusal is not yet a drop");
        assertEquals(1, capture.chunks(bound).size(),
                "and the entity is still held, so a later capture of that terrain can still write it");

        session.promotePacketEntities(LiveCaptureSession.PromotePass.FINISH, 0, 0, 0);

        assertEquals(1, losses(session, "droppedUncaptured"),
                "the finish has nothing behind it, so there the refusal becomes the counted drop");
        assertTrue(capture.chunks(bound).isEmpty(), "and nothing stays held to be counted twice");
    }

    @Test
    void aWriterReportedChunkLossReachesTheVerdict(@TempDir Path temporary) {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);

        assertFalse(session.isPartialSave(0, 0), "a writer that lost nothing leaves the finish clean");
        assertTrue(session.isPartialSave(1, 0), "a region chunk the writer failed to write is a loss to report");
        assertTrue(session.isPartialSave(0, 1), "so is an entity chunk, whose whole mob set is missing from it");
    }

    private static EntityPacketCapture installPacketCapture(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("packetCapture");
        field.setAccessible(true);
        EntityPacketCapture capture = new EntityPacketCapture(false, new SendRangeEstimator(),
                new SendRangeSampler(System::nanoTime, false), Level.OVERWORLD.location().toString());
        field.set(session, capture);
        return capture;
    }

    private static void excludeFromStandaloneWrite(LiveCaptureSession session, UUID uuid) throws Exception {
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.add(uuid);
    }

    private static void spawn(EntityPacketCapture capture, int id, UUID uuid, ChunkPos pos) {
        capture.spawn(id, uuid, pos.toLong(), new EntityPos(pos.getMinBlockX(), 64.0, pos.getMinBlockZ(), 0f, 0f),
                new ClientboundAddEntityPacket(id, uuid, pos.getMinBlockX(), 64.0, pos.getMinBlockZ(), 0f, 0f,
                        EntityType.PIG, 0, Vec3.ZERO));
    }

    private static void captureTerrain(LiveCaptureSession session, ChunkPos pos) throws Exception {
        LongOpenHashSet allCaptured = state(session, "allCaptured");
        allCaptured.add(pos.toLong());
    }

    private static void captureTerrain(LiveCaptureSession session, ResourceKey<Level> dimension, ChunkPos pos)
            throws Exception {
        Map<ResourceKey<Level>, LongOpenHashSet> byDimension = state(session, "capturedByDimension");
        byDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet()).add(pos.toLong());
    }

    private void flushPriorChunk(WorldPaths paths) throws Exception {
        AsyncSaveWriter prior = saveWriter(paths);
        ChunkCodec codec = new VersionAdapterImpl().chunkCodec();
        prior.submitChunk(Level.OVERWORLD, chunk, () -> codec.encode(chestChunk(), registries, false),
                ChunkMerge::merge);
        assertFalse(prior.finish().get(30, TimeUnit.SECONDS).failed(), "the prior chunk reached disk");
    }

    private static int losses(LiveCaptureSession session, String field) throws Exception {
        Field counter = LiveCaptureSession.class.getDeclaredField(field);
        counter.setAccessible(true);
        return counter.getInt(session);
    }

    private static void captureChunk(LiveCaptureSession session, ChunkPos pos, ChunkSnapshotSource snapshot)
            throws Exception {
        Map<ChunkPos, ChunkSnapshotSource> captured = state(session, "captured");
        captured.put(pos, snapshot);
    }

    private static void stashContainer(LiveCaptureSession session, BlockPos pos, CompoundTag holder)
            throws Exception {
        Map<BlockPos, StashHolder> stash = state(session, "containerStash");
        stash.put(pos, StashHolder.of(holder));
    }

    private static void stashLectern(LiveCaptureSession session, BlockPos pos, CompoundTag holder)
            throws Exception {
        Map<BlockPos, StashHolder> stash = state(session, "lecternStash");
        stash.put(pos, StashHolder.of(holder));
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
