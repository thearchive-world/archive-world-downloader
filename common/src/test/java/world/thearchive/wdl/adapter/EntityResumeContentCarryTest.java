// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.VersionAdapterImpl;
import world.thearchive.wdl.compat.bobby.BobbyChunkFilter;
import world.thearchive.wdl.core.CoveredChunkIndex;
import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.SavedChunkIndex;
import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.HeadlessPlatformBridge;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The on-disk guard for a villager whose trades were captured by one download and which occupies a different
 * entity-chunk when a later download resumes. Trades reach the client only through the open trade menu, so the resumed
 * session re-serializes the villager carrying none; the entity read-merge carries {@code "Offers"} forward only inside
 * the chunk file it is writing, so the resumed copy lands in a second chunk file under the same UUID with no trades,
 * and vanilla keeps whichever chunk loads first. The remedy pinned here is that the previously saved trades are read
 * back off disk during the resume and re-applied to the later copy, so which one survives stops mattering.
 *
 * <p>The session-scoped sibling of this guard is {@link EntityVehicleRelocationTest}, which covers a vehicle moving
 * between two flushes of one session, where the retained in-memory holder supplies the re-fold. That holder does not
 * survive a session boundary, which is the hole these cases cover.
 *
 * <p>Asserted against the bytes under {@code entities/} across every chunk the two downloads could have written,
 * because that is the only place the trade-less second copy is visible: every write succeeds and no tally moves.
 * Deliberately never writes the session's folded maps: a case that seeds them itself passes whether or not a resume can
 * populate them, which is the blind spot {@link MerchantStashMergeTest} has by construction.
 */
class EntityResumeContentCarryTest {
    private static final UUID VILLAGER = UUID.fromString("2f6c0f4a-1e88-4d3b-9a52-7c0b6e4d1a93");
    private static final UUID BOAT = UUID.fromString("8d1a44b7-5c02-4e69-b0f1-3a9e77c25d40");
    private static final String FOLDER = "headless";

    private final ContainerSink sink = new ContainerSinkImpl();

    // Instance fields, not constants: ChunkPos's own class initializer reaches a built-in registry, so touching one
    // before the bootstrap in @BeforeAll fails the whole class with "Not bootstrapped".
    private final ChunkPos opened = new ChunkPos(0, 0);
    private final ChunkPos wandered = new ChunkPos(1, 0);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void aResumedVillagerThatChangedChunkCarriesTheTradesTheFirstDownloadSaved(@TempDir Path saves,
            @TempDir Path configDirectory) throws Exception {
        LevelStorageSource source = new LevelStorageSource(saves, saves.resolve("backups"), DataFixers.getDataFixer());
        seedHosts(saves, opened, wandered);

        LiveCaptureSession first = session(configDirectory, DownloadMode.NEW);
        AsyncSaveWriter firstWriter = first.openWorld(source, saveRoot -> {});
        assertNotNull(firstWriter, "the first download must open its world");
        stashMerchant(first, VILLAGER, merchantHolder(offersWith("minecraft:emerald"), 12));
        bufferEntity(first, VILLAGER, opened, EntityFixtures.entity("minecraft:villager", VILLAGER));
        first.flushEntityChunk(firstWriter, opened);
        assertFalse(firstWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the first download drains cleanly");

        Path saveRoot = saves.resolve(FOLDER);
        List<CompoundTag> saved = entitiesOnDisk(saveRoot, VILLAGER, opened, wandered);
        assertEquals(1, saved.size(), "the first download saves exactly one villager");
        assertEquals("minecraft:emerald", firstSellId(saved.get(0)),
                "guard: the first download must actually save the trades, or a later assertion proves nothing");

        LiveCaptureSession second = session(configDirectory, DownloadMode.RESUME);
        AsyncSaveWriter secondWriter = second.openWorld(source, ignoredRoot -> {});
        assertNotNull(secondWriter, "the resume must open the same world");
        // The resume walks past the chunk the villager used to occupy. Submitted before the entity write below, which
        // is what production does: the scan is enqueued when the chunk enters the capture buffer, the entity write
        // only when it later drains, and the writer serves one queue in order.
        secondWriter.submitEntityResumeScan(DimensionType.OVERWORLD, opened);
        // No stash: the resumed session never opened the trade menu, so its serialize carries no offers.
        bufferEntity(second, VILLAGER, wandered, EntityFixtures.entity("minecraft:villager", VILLAGER));
        second.flushEntityChunk(secondWriter, wandered);
        assertFalse(secondWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the resume drains cleanly");

        List<CompoundTag> written = entitiesOnDisk(saveRoot, VILLAGER, opened, wandered);
        assertEquals(2, written.size(), "the resume writes the villager where it now is as well as where it was");
        assertEquals("minecraft:emerald", firstSellId(written.get(0)),
                "the copy in the chunk the first download wrote still carries its trades");
        assertEquals("minecraft:emerald", firstSellId(written.get(1)),
                "and the copy the resume filed under the new chunk carries them too, so which one vanilla keeps on "
                        + "load stops mattering");
    }

    @Test
    void aResumedVehicleThatChangedChunkCarriesTheContentsTheFirstDownloadSaved(@TempDir Path saves,
            @TempDir Path configDirectory) throws Exception {
        LevelStorageSource source = new LevelStorageSource(saves, saves.resolve("backups"), DataFixers.getDataFixer());
        seedHosts(saves, opened, wandered);

        LiveCaptureSession first = session(configDirectory, DownloadMode.NEW);
        AsyncSaveWriter firstWriter = first.openWorld(source, saveRoot -> {});
        assertNotNull(firstWriter, "the first download must open its world");
        stashContainer(first, BOAT, capturedItems(new ItemStack(Items.DIAMOND, 5)));
        bufferEntity(first, BOAT, opened, EntityFixtures.entity("minecraft:chest_boat", BOAT));
        first.flushEntityChunk(firstWriter, opened);
        assertFalse(firstWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the first download drains cleanly");

        Path saveRoot = saves.resolve(FOLDER);
        List<CompoundTag> saved = entitiesOnDisk(saveRoot, BOAT, opened, wandered);
        assertEquals(1, saved.size(), "the first download saves exactly one boat");
        assertTrue(EntityMerge.hasCapturedContent(saved.get(0)),
                "guard: the first download must actually save the contents, or a later assertion proves nothing");

        LiveCaptureSession second = session(configDirectory, DownloadMode.RESUME);
        AsyncSaveWriter secondWriter = second.openWorld(source, ignoredRoot -> {});
        assertNotNull(secondWriter, "the resume must open the same world");
        secondWriter.submitEntityResumeScan(DimensionType.OVERWORLD, opened);
        // No stash: the resumed session never reopened the chest, so its serialize carries nothing.
        bufferEntity(second, BOAT, wandered, EntityFixtures.entity("minecraft:chest_boat", BOAT));
        second.flushEntityChunk(secondWriter, wandered);
        assertFalse(secondWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the resume drains cleanly");

        List<CompoundTag> written = entitiesOnDisk(saveRoot, BOAT, opened, wandered);
        assertEquals(2, written.size(), "the resume writes the boat where it now is as well as where it was");
        assertTrue(EntityMerge.hasCapturedContent(written.get(0)),
                "the copy in the chunk the first download wrote still carries its contents");
        assertTrue(EntityMerge.hasCapturedContent(written.get(1)),
                "and the copy the resume filed under the new chunk carries them too");
    }

    @Test
    void theChunksOwnPriorRecordBeatsOneRecoveredFromElsewhere(@TempDir Path saves, @TempDir Path configDirectory)
            throws Exception {
        LevelStorageSource source = new LevelStorageSource(saves, saves.resolve("backups"), DataFixers.getDataFixer());
        seedHosts(saves, opened, wandered);

        // One download trades with the villager in each of two chunks, so the save holds two records for it and the
        // one in the chunk the resume re-writes is the fresher of the two.
        LiveCaptureSession first = session(configDirectory, DownloadMode.NEW);
        AsyncSaveWriter firstWriter = first.openWorld(source, saveRoot -> {});
        assertNotNull(firstWriter, "the first download must open its world");
        stashMerchant(first, VILLAGER, merchantHolder(offersWith("minecraft:emerald"), 12));
        bufferEntity(first, VILLAGER, opened, EntityFixtures.entity("minecraft:villager", VILLAGER));
        first.flushEntityChunk(firstWriter, opened);
        stashMerchant(first, VILLAGER, merchantHolder(offersWith("minecraft:diamond"), 30));
        bufferEntity(first, VILLAGER, wandered, EntityFixtures.entity("minecraft:villager", VILLAGER));
        first.flushEntityChunk(firstWriter, wandered);
        assertFalse(firstWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the first download drains cleanly");

        Path saveRoot = saves.resolve(FOLDER);
        assertEquals("minecraft:emerald", firstSellId(entitiesOnDisk(saveRoot, VILLAGER, opened).get(0)),
                "guard: the older trades are the ones filed under the first chunk");
        assertEquals("minecraft:diamond", firstSellId(entitiesOnDisk(saveRoot, VILLAGER, wandered).get(0)),
                "guard: and the newer ones under the second, or this case cannot tell the two apart");

        LiveCaptureSession second = session(configDirectory, DownloadMode.RESUME);
        AsyncSaveWriter secondWriter = second.openWorld(source, ignoredRoot -> {});
        assertNotNull(secondWriter, "the resume must open the same world");
        secondWriter.submitEntityResumeScan(DimensionType.OVERWORLD, opened); // banks the older trades
        bufferEntity(second, VILLAGER, wandered, EntityFixtures.entity("minecraft:villager", VILLAGER));
        second.flushEntityChunk(secondWriter, wandered);
        assertFalse(secondWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the resume drains cleanly");

        assertEquals("minecraft:diamond", firstSellId(entitiesOnDisk(saveRoot, VILLAGER, wandered).get(0)),
                "the chunk's own prior record is carried first and the recovered one may only fill what is still "
                        + "missing, so a repair sourced from elsewhere can never overwrite fresher trades");
    }

    @Test
    void aResumedVillagerCarryingClientInventedTradesKeepsTheSavedOnes(@TempDir Path saves,
            @TempDir Path configDirectory) throws Exception {
        LevelStorageSource source = new LevelStorageSource(saves, saves.resolve("backups"), DataFixers.getDataFixer());
        seedHosts(saves, opened, wandered);

        LiveCaptureSession first = session(configDirectory, DownloadMode.NEW);
        AsyncSaveWriter firstWriter = first.openWorld(source, saveRoot -> {});
        assertNotNull(firstWriter, "the first download must open its world");
        stashMerchant(first, VILLAGER, merchantHolder(offersWith("minecraft:emerald"), 12));
        bufferEntity(first, VILLAGER, opened, EntityFixtures.entity("minecraft:villager", VILLAGER));
        first.flushEntityChunk(firstWriter, opened);
        assertFalse(firstWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the first download drains cleanly");

        Path saveRoot = saves.resolve(FOLDER);
        assertEquals("minecraft:emerald", firstSellId(entitiesOnDisk(saveRoot, VILLAGER, opened).get(0)),
                "guard: the first download saved the trades the player actually saw");

        // A client villager invents a random career and a random trade list the moment anything asks it for its
        // offers, so a capture can arrive carrying trades that were never on the server. Built here rather than
        // provoked: on a band whose vanilla guards the client-side write, nothing local produces the shape.
        LiveCaptureSession second = session(configDirectory, DownloadMode.RESUME);
        AsyncSaveWriter secondWriter = second.openWorld(source, ignoredRoot -> {});
        assertNotNull(secondWriter, "the resume must open the same world");
        secondWriter.submitEntityResumeScan(DimensionType.OVERWORLD, opened);
        CompoundTag invented = EntityFixtures.entity("minecraft:villager", VILLAGER);
        invented.put("Offers", offersWith("minecraft:diamond"));
        bufferEntity(second, VILLAGER, opened, invented);
        second.flushEntityChunk(secondWriter, opened);
        assertFalse(secondWriter.finish().get(30, TimeUnit.SECONDS).failed(), "the resume drains cleanly");

        assertEquals("minecraft:emerald", firstSellId(entitiesOnDisk(saveRoot, VILLAGER, opened).get(0)),
                "a client-invented trade list must never reach disk, and must never block the saved trades from "
                        + "being carried forward over it");
    }

    /** The Recipes offers holder vanilla's own {@code MerchantOffers.createTag} writes, one offer selling the item. */
    private static CompoundTag offersWith(String sellId) {
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemStack(Items.EMERALD, 1), ItemFixtures.stack(sellId), 1, 0, 0.0f));
        return offers.createTag();
    }

    private static CompoundTag merchantHolder(CompoundTag offers, int xp) {
        CompoundTag holder = new CompoundTag();
        holder.put("Offers", offers);
        holder.putInt("Xp", xp);
        return holder;
    }

    private static String firstSellId(CompoundTag entity) {
        return entity.getCompound("Offers").getList("Recipes", 10).getCompound(0)
                .getCompound("sell").getString("id");
    }

    /**
     * Every on-disk entity tag carrying {@code uuid}, across each entity-chunk the two downloads could have reached.
     *
     * <p>Entities live inside the region chunk under {@code Level.Entities} here, so the on-disk oracle reads the host
     * chunk the fold wrote into rather than a separate {@code entities/} region.
     */
    private static List<CompoundTag> entitiesOnDisk(Path saveRoot, UUID uuid, ChunkPos... positions) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(saveRoot);
        List<CompoundTag> found = new ArrayList<>();
        try (IOWorker storage = paths.openRegionStorage(DimensionType.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                CompoundTag chunkTag = storage.load(pos);
                if (chunkTag == null || !(chunkTag.get("Level") instanceof CompoundTag)) {
                    continue;
                }
                Tag rawEntities = ((CompoundTag) chunkTag.get("Level")).get("Entities");
                if (!(rawEntities instanceof ListTag)) {
                    continue;
                }
                ListTag entities = (ListTag) rawEntities;
                for (int i = 0; i < entities.size(); i++) {
                    Tag rawEntity = entities.get(i);
                    if (rawEntity instanceof CompoundTag
                            && uuid.equals(EntityMerge.readUuid((CompoundTag) rawEntity))) {
                        found.add((CompoundTag) rawEntity);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Write an empty host chunk at each position before any download opens. Entities fold into the region chunk that
     * holds the terrain here, so a fold with no host on disk is a counted loss rather than a write, and without this
     * the fixture would prove nothing about carrying content forward.
     */
    private static void seedHosts(Path saves, ChunkPos... positions) throws Exception {
        WorldPaths paths = new VersionAdapterImpl().worldPaths(saves.resolve(FOLDER));
        try (IOWorker storage = paths.openRegionStorage(DimensionType.OVERWORLD)) {
            for (ChunkPos pos : positions) {
                CompoundTag host = new CompoundTag();
                host.put("Level", new CompoundTag());
                storage.store(pos, host).join();
            }
            storage.synchronize().join();
        }
    }

    /**
     * A session targeting one fixed folder name so a resume lands on the folder the first download wrote. The resume
     * backup is off because it zips the whole folder on the writer thread before the drain, which this case does not
     * exercise and which would only slow it.
     */
    private static LiveCaptureSession session(Path configDirectory, DownloadMode mode) {
        Properties properties = new Properties();
        properties.setProperty("captureEntities", "false");
        properties.setProperty("captureContainers", "false");
        properties.setProperty("zipOnResume", "false");
        WdlConfig config = WdlConfig.parse(properties);
        return new LiveCaptureSession(new VersionAdapterImpl(), new HeadlessPlatformBridge(configDirectory),
                config, null, DimensionType.OVERWORLD, DimensionType.OVERWORLD,
                new DownloadTarget(FOLDER, null, mode), new SavedChunkIndex(),
                new CoveredChunkIndex(), new SendRangeEstimator(), false, false, BobbyChunkFilter.INACTIVE,
                () -> {});
    }

    private CompoundTag capturedItems(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, stack);
        return sink.captureItems(items);
    }

    private static void stashContainer(LiveCaptureSession session, UUID uuid, CompoundTag holder) throws Exception {
        Map<UUID, CompoundTag> stash = state(session, "entityContainerStash");
        stash.put(uuid, holder);
    }

    private static void stashMerchant(LiveCaptureSession session, UUID uuid, CompoundTag holder) throws Exception {
        Map<UUID, CompoundTag> stash = state(session, "merchantStash");
        stash.put(uuid, holder);
    }

    private static void bufferEntity(LiveCaptureSession session, UUID uuid, ChunkPos pos, CompoundTag tag)
            throws Exception {
        EntityBuffer buffer = state(session, "entityBuffer");
        buffer.accumulate(uuid, pos, tag);
    }

    /**
     * One of the session's capture-state collections, to seed. Reflective because every production path that fills one
     * runs behind the client singleton (a trade-menu open, a packet-driven promote), and widening the fields would
     * publish mutable capture state to the whole package for a test's convenience.
     */
    @SuppressWarnings("unchecked")
    private static <T> T state(LiveCaptureSession session, String name) throws Exception {
        Field field = LiveCaptureSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(session);
    }
}
