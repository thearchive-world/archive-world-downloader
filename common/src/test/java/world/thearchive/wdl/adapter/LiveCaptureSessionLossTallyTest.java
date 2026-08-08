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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
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
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The three per-item soft-failure tallies the capture drain and the finish path feed, each pinned at both of its
 * increment sites: a container holder's map remap and a framed map item's remap (the map-remap tally), a ridden mount's
 * fold and an entity-chunk's fold (the entity-container tally), and the buffered chunk's submit thunk and the orphan
 * sweep's rewrite thunk (the block-container tally). Every one of the six is a data loss the user cannot see from
 * in-game: the items the player opened, or the map a frame renders, are absent from a download the completion record
 * would otherwise call clean. Each counter-bearing test asserts both halves of the wiring, since neither alone is
 * enough: the named counter moved (the verdict sums every term, so a site rewired to a sibling counter still reads
 * partial), and the verdict turned partial (a counter no term of the sum reads is a loss nothing reports). The
 * structural entity loss follows the same shape, driven the whole way from the refused entity-chunk flush that produces
 * it. The pair the writer hands in has no counter to name here, since both are parameters rather than fields, so only
 * the verdict half is pinned; their counting is the writer's own test.
 *
 * <p>Three more losses carry the same both-halves shape: a chunk whose terrain snapshot throws (whose terrain the
 * reopened world simply does not have, in a save reported complete), a single entity whose encode throws, and the
 * frames an aborted finish drain leaves held in a captured chunk. The entity pair is deliberately two tests rather than
 * one, because what they pin is a split: the sink refusing an entity and the encode throwing both return the same null,
 * and only the second may reach the verdict, so the refusal case asserts a clean finish as hard as its sibling asserts
 * a partial one. The chunk case is likewise two, since the counted unit is the distinct position: the square retries a
 * failing chunk every tick it stays loaded, so a per-attempt tally would report tens of losses for one lost chunk.
 *
 * <p>One test here is not a tally test: the loss it guards, a dimension-scoped store detached from its per-dimension
 * map, has no counter at all.
 *
 * <p>Two mechanisms, split on what each reaches. The drain and mount-fold entry points are driven directly,
 * package-private for that, since every production caller runs behind the client singleton. The capture state they
 * consume is seeded reflectively: a menu open and a live chunk capture are the only paths that fill those collections
 * and both need a client, while widening the fields would publish mutable capture state to the whole package for a
 * test's convenience. A merge that throws and an entities envelope that comes back null arrive as adapters passed to
 * the constructor, so no production seam stands in for either.
 *
 * <p>Five boundaries. These tests own the writer and hand it in, so nothing here would notice if the production flush
 * pump stopped passing the writer it opened. Driving each entry point directly has the same shape of cost one level up:
 * nothing here would notice a production caller that stopped calling one, and the pair this class hand-sequences (the
 * whole-buffer drain, then the reconciliation that reads what it fed) is ordered by {@code finish()} alone. Of the
 * block-container site's three summed merge tallies, two are pinned here, the container and the lectern; the
 * interaction-holder term stays zero while the fixture leaves the interaction capture off, which it must, since arming
 * one publishes a process-wide capture. The user-visible outcome each message names is read back off disk for one of
 * them only, the buffered chunk's standing chest; that a map renders blank and that a mount saves empty are asserted a
 * tier away, so the messages here name a consequence this class establishes the counting of rather than the rendering.
 * And every flush drop counts as reconstructed, since seeding the entity buffer directly records no source for the
 * entity, so the primed arm of the structural count is pinned only by the reconciliation record's own test. Each gap is
 * a client-side or a leaf-level path, covered by the capture gametests or by that record's test.
 *
 * <p>The chunk-snapshot and aborted-drain losses add one more of the same shape, and it is the one the split try in the
 * capture square exists for: what is driven here is the recorder each catch calls, not the catch, so nothing here would
 * notice a catch that stopped calling one. Both recorders are one statement from their site and the distinction the
 * chunk one turns on, that only a throw out of the snapshot itself loses the terrain, is visible at that site rather
 * than inside the recorder.
 */
class LiveCaptureSessionLossTallyTest {
    private static final UUID VEHICLE = UUID.fromString("6b1d5f2c-9a30-4e11-b8c7-5d0e3a71f402");
    private static final UUID FRAME = UUID.fromString("2f9c4b18-7d60-4a35-9e21-0c7b6d5a3e14");

    private static RegistryAccess.Frozen registries;
    private final ContainerSink sink = new ContainerSinkImpl();
    // Instance fields, not constants: ChunkPos's own class initializer reaches a built-in registry, so touching
    // one before the bootstrap in @BeforeAll fails the whole class with "Not bootstrapped".
    private final ChunkPos chunk = new ChunkPos(0, 0);
    // The holder's x and z differ so that an axis confusion in a block-entity locator misses the match rather
    // than landing on it, and a no-match is counted as neither merged nor failed, which would read as a clean
    // download. The chunk stays at the origin because the synthetic snapshot is built there, so an axis
    // confusion in a chunk-position locator is still invisible here; closing that needs a positioned snapshot.
    private final BlockPos chest = new BlockPos(2, 64, 13);

    private final BlockPos lectern = new BlockPos(9, 64, 4);

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen(); // vanilla statics, which a chunk encode and an item capture both need
    }

    /**
     * A session with no bound level, which is all the drain paths below need. Entity and container capture are off so
     * the constructor publishes no process-wide capture into the static activation slots; a test that turns either on
     * leaves a session published there for the rest of the suite JVM, since only {@code finish()} clears it. The
     * toggles are asserted rather than assumed: an unrecognized config key falls back to the default, which is on for
     * both, so a silent key rename would arm every session this fixture builds.
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

    /**
     * A session with the user's omit-void-chunks setting on, the one arm under which a captured position can be dropped
     * rather than written. Off by default, so the fixture asserts it took.
     */
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

    /** A captured chunk carrying nothing: all air, no block entities, the void-skip's own subject. */
    private ChunkSnapshotSource voidChunk() {
        return SyntheticChunks.withBlockAt(registries, new BlockPos(0, 64, 0), Blocks.AIR.defaultBlockState());
    }

    /** The save layout for a fresh folder, with both storage directories already present. */
    private static WorldPaths paths(Path save) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(save);
        Files.createDirectories(paths.regionDirectory(Level.OVERWORLD));
        Files.createDirectories(paths.entitiesDirectory(Level.OVERWORLD));
        return paths;
    }

    /**
     * A writer over real region and entities storages, which the block-container sites need: both are incremented
     * inside a thunk the writer resolves, and the orphan sweep's thunk runs only after its chunk has been read back off
     * disk, which a stub storage would never satisfy.
     */
    private static AsyncSaveWriter saveWriter(WorldPaths paths) {
        return new AsyncSaveWriter(
                dimension -> new SimpleRegionStorage(paths.regionStorageInfo(dimension),
                        paths.regionDirectory(dimension), DataFixers.getDataFixer(), false, DataFixTypes.CHUNK),
                dimension -> new SimpleRegionStorage(paths.entitiesStorageInfo(dimension),
                        paths.entitiesDirectory(dimension), DataFixers.getDataFixer(), false,
                        DataFixTypes.ENTITY_CHUNK),
                () -> {},
                (chunksFailed, entityChunksFailed) -> {},
                () -> null,
                () -> {},
                new SaveProgress());
    }

    /** An archive that fails every id resolution the same way, the deterministic remap loss both paths absorb. */
    private static MapArchive throwingArchive() {
        return new MapArchive(MapManifest.empty(), id -> {
            throw new IllegalStateException("the map image could not be read");
        }, (archiveId, dataTag) -> {});
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

    /** The container merge always throws: the deterministic per-holder merge loss both container tallies absorb. */
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

    /** The lectern merge always throws, the second per-band sink call the block-container tally sums. */
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

    /**
     * The entities envelope always comes back null: the sink-refused encode that drops a whole drained entity-chunk,
     * which is one of the two structural losses the reconciliation counts.
     */
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

    /** The single-entity encode always throws: the reject class the sink cannot pre-sanitize away. */
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

    /**
     * The single-entity encode always refuses: the empty-envelope null the production sink returns for a passenger
     * saved nested, a removed entity, a player-only vehicle, or a non-serializable type.
     */
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

    /**
     * An entity with no level behind it, which is all the single-entity encode needs from its argument: the sinks above
     * never look at it, and the loss line reads only its UUID, which the Entity constructor assigns.
     */
    private static final class BareEntity extends Entity {
        private BareEntity() {
            super(EntityTypes.PIG, HeadlessLevel.get());
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(ValueInput input) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {}

        @Override
        public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
            return false;
        }
    }

    /** A captured chunk carrying a chest and a lectern block entity, the two folds' match targets. */
    private ChunkSnapshotSource chestChunk() {
        return SyntheticChunks.fullWithBlockEntities(registries, true,
                List.of(blockEntity("minecraft:chest", chest.getX(), chest.getY(), chest.getZ()),
                        blockEntity("minecraft:lectern", lectern.getX(), lectern.getY(), lectern.getZ())));
    }

    /** The block entity of the given type in the chunk written to {@code paths}, or null when absent. */
    private static @Nullable CompoundTag blockEntityOnDisk(WorldPaths paths, ChunkPos pos, BlockPos at)
            throws Exception {
        try (SimpleRegionStorage storage = new SimpleRegionStorage(paths.regionStorageInfo(Level.OVERWORLD),
                paths.regionDirectory(Level.OVERWORLD), DataFixers.getDataFixer(), false, DataFixTypes.CHUNK)) {
            CompoundTag chunkTag = storage.read(pos).join().orElseThrow(() -> new AssertionError("chunk not on disk"));
            return findByPosOrNull(chunkTag, at.getX(), at.getY(), at.getZ());
        }
    }

    /** A captured 27-slot container holder carrying one stack, the open-time bundle a fold merges. */
    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items, registries);
    }

    /** A serialized entity tag carrying just its UUID, which is all the folds and the envelope read. */
    private static CompoundTag entity(UUID uuid) {
        CompoundTag entity = new CompoundTag();
        entity.store("UUID", UUIDUtil.CODEC, uuid);
        return entity;
    }

    /** A serialized entity tag whose displayed {@code "Item"} is {@code stack} (a framed or dropped item). */
    private CompoundTag entityDisplaying(UUID uuid, ItemStack stack) {
        CompoundTag tag = entity(uuid);
        tag.put("Item", (CompoundTag) ((ListTag) holderOf(sink, registries, stack).get("Items")).get(0));
        return tag;
    }

    /**
     * Not a tally test. Every store whose contents belong to one dimension lives in a per-dimension map and is reached
     * through a field the constructor's bind points at that map's instance for the starting dimension. The placeholder
     * initializer each field carries means an omitted registration or an omitted bind is not a build error: the field
     * simply stays on a placeholder no map holds, and nothing looks wrong from inside the bound dimension, because the
     * capture paths write and read that same detached field. What breaks is every reader of the maps.
     * {@code totalCapturedChunks} sums an empty one, so the headline count and the per-dimension breakdown read zero
     * and finish takes its nothing-captured exit, completing without a writer. The first portal then drops the
     * placeholder outright, so the dimension left keeps none of its positions.
     */
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
     * entry at all, which equality catches too, but a swap that assigned a fresh instance instead of the map's own
     * would leave two empty collections that compare equal. The non-null check is what keeps this from passing on two
     * nulls if a store ever loses its placeholder initializer.
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

    /**
     * The success side of the same fold, which the throwing one cannot see: the sink is pure and returns a merged copy,
     * so a fold that dropped the returned tag would write the mount with empty contents and count nothing, the loss the
     * tally test above would still call clean.
     */
    @Test
    void aRiddenMountsFoldedContentsReachTheTagItReturns(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        stashEntityContainer(session, VEHICLE, capturedItems(new ItemStack(Items.DIAMOND, 5)));

        CompoundTag folded = session.foldRidingVehicleContents(entity(VEHICLE));

        assertNotNull(folded.get("Items"),
                "the contents the player opened are folded into the tag the mount saves as");
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

    /**
     * The lectern term of the same site, which the chest tests leave at zero: it is a second per-band sink call summed
     * into one counter, so a lost book would report the download clean if only the container term were wired.
     */
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

    /**
     * The chain from a lost entity-chunk flush to the verdict, which is the whole point of the structural-loss count: a
     * drained chunk the sink refuses is gone from the save, so the reconciliation that reports it must also make the
     * finish partial. Both halves are production paths; only the packet capture is installed.
     */
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

    /**
     * The whole-chunk loss the sum had no term for at all: a snapshot that throws leaves the position in neither the
     * buffer nor the captured set, so it is absent from the overlay, absent from the totals, and absent from the
     * writer's own write tally, so the reopened world falls back to its own generator there in a download reported
     * complete.
     */
    @Test
    void aChunkWhoseSnapshotThrowsLandsInTheChunkCaptureTally(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        assertFalse(session.isPartialSave(0, 0), "nothing has been captured, so the finish reads clean");

        session.recordChunkCaptureLoss(chunk, new IllegalStateException("the chunk snapshot blew up"));

        assertEquals(1, losses(session, "chunksCaptureFailed"),
                "the terrain never reached the buffer, so the chunk missing from the save is counted as one");
        assertTrue(session.isPartialSave(0, 0), "and one missing chunk is enough to make the finish partial");
    }

    /**
     * The dedup the counted unit rests on. Neither guard above the snapshot call holds for a position whose snapshot
     * threw, since it enters neither collection, so the square retries it every tick it stays loaded and a
     * deterministic failure would otherwise add a loss per tick for minutes. The counted unit is the distinct position,
     * not the attempt.
     */
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

    /**
     * The loss half of the split in the single-entity encode: an encode that throws destroys an entity the sink would
     * have written, so it must reach the verdict through the structural count the reconciliation hands over. Driven
     * from the encode itself, so a rewiring of the counters underneath it is caught here rather than only in the
     * record's own arithmetic.
     */
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

    /**
     * The finish re-offer's own per-entity catch, which covers the hops around the encode that the encode's own
     * isolation never sees. A throw in any of them destroys the refused mount the re-offer exists to recover, so it has
     * to reach the same structural count the encode's throw does. Two entities, so a catch hoisted out of the
     * per-entity loop, which would destroy every refusal behind the first, cannot pass as one loss.
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
     * The other side of that catch: it spans past the buffer write, so a throw can land on an entity whose tag is
     * already bound for disk, and counting that one would report a loss the download did not take. Also the guard
     * against a counter placed at the top of the loop body or in a finally, either of which would turn every download
     * that recovers a refused mount partial.
     */
    @Test
    void aRetryThrowOnAnEntityAlreadyBoundForDiskCountsNoLoss(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        installPacketCapture(session);
        Set<UUID> saved = state(session, "savedEntities");
        saved.add(VEHICLE); // in a tag bound for disk, whether from this retry's own write or an earlier nesting
        Set<UUID> refused = state(session, "primeRefusedEntities");
        refused.add(VEHICLE);

        session.retryRefusedPrimes();
        session.reportEntityReconciliation();

        assertEquals(0, losses(session, "primeEncodeFailures"), "the entity reaches disk, so the throw counts nothing");
        assertFalse(session.isPartialSave(0, 0), "and the download that saved it reports clean");
    }

    /**
     * The other half of the same split, and the reason it had to be made: the sink refusing an entity is one of
     * vanilla's own non-saves, so the identical null return must leave the download clean. A shared counter promoted to
     * a term would turn every download that primes a mounted mob partial.
     */
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

    /**
     * The finish drain's abort, whose held remainder the reconciliation would otherwise fold into the residual its own
     * line calls reload churn rather than loss. A frame left in a captured chunk is the one case where that residual IS
     * loss: it was received, the finish is the last pass, and nothing will write it.
     */
    @Test
    void framesLeftByAnAbortedFinishDrainReachTheVerdictThroughTheStructuralLossTally(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        // The third entity sits in a chunk whose terrain was never captured, so a count that ignored the
        // privacy gate and simply took the accumulator's size would read three.
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
     * and lose its terrain as well, which is a terrain loss the entity failure had not itself caused. Nothing pins the
     * order of those two steps, since {@code finish()} alone sequences them, so what this pins is the reason the order
     * matters.
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

    /**
     * The control for the test above, which would otherwise pass on a fixture that was never void to begin with: the
     * same chunk with no entity held for it is dropped, so the held frame is what made the difference.
     */
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

    /**
     * A mount captured into the player's RootVehicle reaches disk inside level.dat, so its abandoned frame is not a
     * loss. The drain's own promote filters that mount out before counting anything; the abort sweep has to filter it
     * too, or a download that saved the boat reports having lost it.
     */
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
     * A frame held for another dimension whose own terrain that dimension DID capture is a loss: the privacy gate would
     * have allowed the write and nothing performed it. The chunk is captured in the other dimension and not in the
     * bound one, which is also the collision the dimension-blind read produced in reverse: on a nether-to-overworld
     * transit the leaked key is one eighth the magnitude of the coordinates it maps to, so it lands inside what a
     * player who explored spawn has already captured. Both sweeps here would move their counts if either tested the key
     * without its dimension.
     */
    @Test
    void aFrameHeldForAnotherDimensionOnCapturedTerrainReachesTheVerdict(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        captureTerrain(session, Level.NETHER, chunk); // captured THERE, deliberately not in the bound dimension
        capture.enterDimension(Level.NETHER.identifier().toString());
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

    /**
     * The same held frame on terrain its own dimension never captured is the ordinary privacy-gate refusal, and must
     * leave the download clean. This is the routine shape: a player who leaves a dimension while capture is still
     * behind, and does not return, has nothing in the save for that terrain either, so there was never a write to lose.
     * Charging it as loss would turn a normal portal trip into a partial download.
     */
    @Test
    void aFrameHeldForAnotherDimensionOnUncapturedTerrainStaysBenign(@TempDir Path temporary) throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        capture.enterDimension(Level.NETHER.identifier().toString());
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

    /**
     * The privacy gate's hold survives the drain a dimension change runs, and only the finish converts it into a drop.
     * The distinction is which pass has one behind it: the session keeps every dimension's captured positions and the
     * rebind restores them if the player walks back in, so terrain not captured when they left can still be captured on
     * a return trip and write what was held. A drain that dropped it at the portal would file it under the counter that
     * means "correctly not saved" while the download could still have saved it.
     */
    @Test
    void theDimensionChangeDrainHoldsWhatTheGateRefusesAndOnlyTheFinishDropsIt(@TempDir Path temporary)
            throws Exception {
        LiveCaptureSession session = session(new VersionAdapterImpl(), temporary);
        EntityPacketCapture capture = installPacketCapture(session);
        String bound = Level.OVERWORLD.identifier().toString();
        // Terrain deliberately NOT captured for this chunk, which is the whole subject: the gate refuses it.
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

    /**
     * Install the packet capture the reconciliation reads. Set directly rather than by turning captureEntities on: the
     * constructor publishes a capture it creates into the process-wide activation slot, which only {@code finish()}
     * clears, so an armed fixture would leave this session live for the rest of the suite JVM.
     */
    private static EntityPacketCapture installPacketCapture(LiveCaptureSession session) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField("packetCapture");
        field.setAccessible(true);
        EntityPacketCapture capture = new EntityPacketCapture(false, new SendRangeEstimator(),
                new SendRangeSampler(System::nanoTime, false), Level.OVERWORLD.identifier().toString());
        field.set(session, capture);
        return capture;
    }

    /**
     * Hold {@code uuid} back from the standalone entity write, which production does when it captures a ridden mount
     * into the player's RootVehicle, behind the client reads this fixture does without.
     */
    private static void excludeFromStandaloneWrite(LiveCaptureSession session, UUID uuid) throws Exception {
        Set<UUID> excluded = state(session, "excludedRootVehicleUuids");
        excluded.add(uuid);
    }

    /** Feed one held spawn frame, the state the finish drain consumes and an aborted one leaves behind. */
    private static void spawn(EntityPacketCapture capture, int id, UUID uuid, ChunkPos pos) {
        capture.spawn(id, uuid, pos.pack(), new EntityPos(pos.getMinBlockX(), 64.0, pos.getMinBlockZ(), 0f, 0f),
                new ClientboundAddEntityPacket(id, uuid, pos.getMinBlockX(), 64.0, pos.getMinBlockZ(), 0f, 0f,
                        EntityTypes.PIG, 0, Vec3.ZERO, 0.0));
    }

    /**
     * Mark {@code pos}'s terrain captured, which is the entity privacy gate: a held frame in an uncaptured chunk was
     * never savable, so only one in a captured chunk is a loss.
     */
    private static void captureTerrain(LiveCaptureSession session, ChunkPos pos) throws Exception {
        LongOpenHashSet allCaptured = state(session, "allCaptured");
        allCaptured.add(pos.pack());
    }

    /**
     * Mark {@code pos}'s terrain captured in {@code dimension} rather than in the bound one. That is the set the finish
     * consults for a frame held for another world, and it is a different set from the bound dimension's, which is the
     * whole distinction the two sweeps turn on.
     */
    private static void captureTerrain(LiveCaptureSession session, ResourceKey<Level> dimension, ChunkPos pos)
            throws Exception {
        Map<ResourceKey<Level>, LongOpenHashSet> byDimension = state(session, "capturedByDimension");
        byDimension.computeIfAbsent(dimension, key -> new LongOpenHashSet()).add(pos.pack());
    }

    /**
     * Write the chest's chunk to disk, the on-disk prior the orphan sweep folds onto. Through a writer of its own
     * rather than the session's, since the session opens its world once.
     */
    private void flushPriorChunk(WorldPaths paths) throws Exception {
        AsyncSaveWriter prior = saveWriter(paths);
        ChunkCodec codec = new VersionAdapterImpl().chunkCodec();
        prior.submitChunk(Level.OVERWORLD, chunk, () -> codec.encode(chestChunk(), registries, false),
                ChunkMerge::merge);
        assertFalse(prior.finish().get(30, TimeUnit.SECONDS).failed(), "the prior chunk reached disk");
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

    /**
     * One of the session's capture-state collections, to seed. Reflective because every production path that fills one
     * runs behind the client singleton (a menu open, a live chunk capture), and widening the fields would publish
     * mutable capture state to the whole package for a test's convenience.
     */
    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }
}
