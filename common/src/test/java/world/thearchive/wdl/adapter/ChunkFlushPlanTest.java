// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.adapter.impl.LecternSinkImpl;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The guard for the per-chunk flush wiring: the arguments a flushed chunk reads off its snapshot and hands to the
 * writer, and the merges the writer thunks apply.
 *
 * <p>These call sites are where three separate defects have lived with the whole suite green, because a merge that has
 * good tests proves nothing about whether production still calls it, calls it with the arguments that make it
 * chunk-specific, or calls it at all. Each case here drives the wiring and asserts the artifact, so reverting a line to
 * the version that preceded it turns one red.
 */
class ChunkFlushPlanTest {
    private static RegistryAccess registries;

    private final ContainerSink containerSink = new ContainerSinkImpl();
    private final LecternSink lecternSink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    /** Built on demand, never as a static initializer: ChunkPos static init needs the vanilla bootstrap. */
    private static ChunkPos origin() {
        return new ChunkPos(0, 0);
    }

    private static CompoundTag bookshelf(int x, int y, int z, int... slots) {
        CompoundTag tag = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, x, y, z);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        tag.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return tag;
    }

    private static List<Integer> slotsOf(CompoundTag blockEntityTag) {
        return blockEntityTag.getListOrEmpty("Items").compoundStream()
                .map(entry -> (int) entry.getByteOr("Slot", (byte) -1))
                .sorted()
                .toList();
    }

    private CompoundTag itemsHolder(String itemId) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, ItemFixtures.stack(itemId));
        return containerSink.captureItems(items, registries);
    }

    private static CompoundTag discHolder(String discId) {
        CompoundTag holder = new CompoundTag();
        holder.put("RecordItem", ItemFixtures.itemTag(discId));
        return holder;
    }

    private static Map<BlockPos, CompoundTag> stash(BlockPos pos, CompoundTag holder) {
        Map<BlockPos, CompoundTag> stash = new LinkedHashMap<>();
        stash.put(pos, holder);
        return stash;
    }

    /** A sink whose merge throws, the band-merge failure the fold has to isolate and count. */
    private static final ContainerSink THROWING_CONTAINER_SINK = new ContainerSink() {
        @Override
        public CompoundTag captureItems(NonNullList<ItemStack> items, RegistryAccess registries) {
            throw new AssertionError("the failure path never serializes items");
        }

        @Override
        public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedItemsHolder) {
            throw new IllegalStateException("a band container merge blew up");
        }
    };

    private static final LecternSink THROWING_LECTERN_SINK = new LecternSink() {
        @Override
        public CompoundTag captureBook(ItemStack book, int page, RegistryAccess registries) {
            throw new AssertionError("the failure path never serializes a book");
        }

        @Override
        public CompoundTag merge(CompoundTag blockEntityTag, CompoundTag capturedBookHolder) {
            throw new IllegalStateException("a band lectern merge blew up");
        }
    };

    @Test
    void theReadMergeHonorsTheBookshelfOccupancyItWasBuiltWith() {
        // Reverting the writer to the hardcoded two-argument merge left the whole suite green once, because
        // every other case passed the same merge that one used. The occupancy is what makes it chunk-specific.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(4, 64, 9).asLong(), 0b110);

        int merged = ChunkFlushPlan.readMerge(occupancy, LongSet.of(), LongSet.of()).merge(onDisk, fresh);

        assertEquals(1, merged, "the shelf carried forward, so the merge really ran");
        assertEquals(List.of(1, 2), slotsOf(findByPos(fresh, 4, 64, 9)),
                "slot 0 reads empty in the block-state the occupancy carries, so it is not carried back");
    }

    /**
     * The replaced positions are held per dimension and handed to the writer per chunk, so the narrowing is both what
     * detaches them from the live set and what keeps one chunk's placements out of another's merge.
     */
    @Test
    void theReplacedPositionsHandedOverAreThisChunksOwn() {
        BlockPos inside = new BlockPos(3, 64, 7);
        BlockPos elsewhere = new BlockPos(300, 64, 700);
        LongOpenHashSet replaced = new LongOpenHashSet();
        replaced.add(inside.asLong());
        replaced.add(elsewhere.asLong());

        LongSet forChunk = ChunkFlushPlan.replacedIn(new ChunkPos(inside), replaced);

        assertTrue(forChunk.contains(inside.asLong()), "a placement in this chunk reaches this chunk's merge");
        assertFalse(forChunk.contains(elsewhere.asLong()), "and one in another chunk does not");
    }

    @Test
    void theReadMergeHonorsTheOpenTimePositionsItWasBuiltWith() {
        // Both fresh sides read as the client defaults, which is the whole difficulty: only the position set
        // says whether that default was captured from a re-opened menu or never captured at all.
        CompoundTag onDisk = chunkTagWith(crafter(6, 64, 6, new int[] { 2, 5 }, 1));
        CompoundTag freshReopened = chunkTagWith(crafter(6, 64, 6, new int[0], 0));
        CompoundTag freshRewalked = chunkTagWith(crafter(6, 64, 6, new int[0], 0));
        LongSet openTimeCaptured = ChunkMerge.capturedPositions(List.of(new BlockPos(6, 64, 6)));

        int withPosition = ChunkFlushPlan.readMerge(ChunkMerge.occupancyMap(), openTimeCaptured, LongSet.of())
                .merge(onDisk.copy(), freshReopened);
        int withoutPosition = ChunkFlushPlan.readMerge(ChunkMerge.occupancyMap(), LongSet.of(), LongSet.of())
                .merge(onDisk.copy(), freshRewalked);

        assertEquals(0, withPosition, "a crafter re-opened this session captured its own state, nothing carries back");
        assertArrayEquals(new int[0],
                findByPos(freshReopened, 6, 64, 6).getIntArray("disabled_slots").orElseThrow(),
                "so the state the re-open captured survives");
        assertEquals(1, withoutPosition, "and without the position the prior state does carry forward");
        assertArrayEquals(new int[] { 2, 5 },
                findByPos(freshRewalked, 6, 64, 6).getIntArray("disabled_slots").orElseThrow(),
                "which is what proves the set is read rather than ignored");
    }

    @Test
    void theComposedReadMergeDerivesTheOccupancyFromTheSnapshotItWasGiven() {
        // The derivation, not just its use: reading the occupancy under a condition, or handing over an empty
        // one, is the shape that broke, and it is neither a deletion nor a compile error.
        BlockPos shelfPos = new BlockPos(4, 64, 9);
        BlockState shelf = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(1), true)
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(2), true);
        ChunkSnapshotSource snapshot = SyntheticChunks.withBlockEntityAt(registries, shelfPos, shelf,
                blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, shelfPos.getX(), shelfPos.getY(), shelfPos.getZ()));
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9));

        int merged = ChunkFlushPlan.readMerge(snapshot, List.of(), LongSet.of()).merge(onDisk, fresh);

        assertEquals(1, merged, "the shelf carried forward, so the composed merge really ran");
        assertEquals(List.of(1, 2), slotsOf(findByPos(fresh, 4, 64, 9)),
                "and slot 0 was dropped, which only the occupancy read off this snapshot can decide");
    }

    @Test
    void theComposedReadMergeDerivesTheOpenTimePositionsFromTheLandingHolders() {
        BlockPos crafterPos = new BlockPos(6, 64, 6);
        ChunkSnapshotSource snapshot = snapshotOf(blockEntity("minecraft:crafter", 6, 64, 6));
        CompoundTag onDisk = chunkTagWith(crafter(6, 64, 6, new int[] { 2, 5 }, 1));
        CompoundTag freshReopened = chunkTagWith(crafter(6, 64, 6, new int[0], 0));
        CompoundTag freshRewalked = chunkTagWith(crafter(6, 64, 6, new int[0], 0));

        int landed = ChunkFlushPlan.readMerge(snapshot, List.of(crafterPos), LongSet.of())
                .merge(onDisk.copy(), freshReopened);
        int none = ChunkFlushPlan.readMerge(snapshot, List.of(), LongSet.of()).merge(onDisk.copy(), freshRewalked);

        assertEquals(0, landed, "a position named as landing captured its own state, so nothing carries back");
        assertEquals(1, none, "and one not named carries the prior state forward");
        assertArrayEquals(new int[] { 2, 5 },
                findByPos(freshRewalked, 6, 64, 6).getIntArray("disabled_slots").orElseThrow(),
                "which is what proves the landing list reaches the merge rather than being dropped");
    }

    @Test
    void landingHolderPositionsIsWhatTheComposedReadMergeShouldBeGiven() {
        // The list handed to the composed merge has to be the LANDING holders, not every drained one: a holder
        // the fold drops writes no state, and calling it captured would blank what an earlier visit saved.
        CompoundTag crafterTag = blockEntity("minecraft:crafter", 6, 64, 6);
        ChunkSnapshotSource snapshot = snapshotOf(crafterTag);
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(6, 64, 6), itemsHolder("minecraft:diamond"));
        holders.put(new BlockPos(9, 64, 9), itemsHolder("minecraft:dirt")); // no block entity captured here

        List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(snapshot, holders);

        assertEquals(List.of(new BlockPos(6, 64, 6)), landing,
                "the position with no captured block entity is not a landing holder");
    }

    /** A crafter carrying the two state keys vanilla writes unconditionally, at the given values. */
    private static CompoundTag crafter(int x, int y, int z, int[] disabledSlots, int triggered) {
        CompoundTag tag = blockEntity("minecraft:crafter", x, y, z);
        tag.putIntArray("disabled_slots", disabledSlots);
        tag.putInt("triggered", triggered);
        return tag;
    }

    @Test
    void foldChunkStashesMergesEveryStashOntoItsOwnBlockEntity() {
        CompoundTag chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:lectern", 2, 64, 2),
                blockEntity("minecraft:jukebox", 3, 64, 3));

        Map<BlockPos, CompoundTag> containers = stash(new BlockPos(1, 64, 1), itemsHolder("minecraft:diamond"));
        Map<BlockPos, CompoundTag> lecterns = stash(new BlockPos(2, 64, 2),
                lecternSink.captureBook(ItemFixtures.writtenBook(2), 1, registries));
        Map<BlockPos, CompoundTag> holders = stash(new BlockPos(3, 64, 3), discHolder("minecraft:music_disc_cat"));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), containerSink, lecternSink,
                containers, lecterns, holders);

        assertEquals(3, tally.merged(), "each of the three stashes folded its own block entity");
        assertEquals(0, tally.failed());
        assertFalse(findByPos(chunkTag, 1, 64, 1).getListOrEmpty("Items").isEmpty(), "the chest gained its items");
        assertTrue(findByPos(chunkTag, 2, 64, 2).contains("Book"), "the lectern gained its book");
        assertTrue(findByPos(chunkTag, 3, 64, 3).contains("RecordItem"), "the jukebox gained its disc");
        assertTrue(containers.isEmpty() && lecterns.isEmpty() && holders.isEmpty(),
                "and every stash is drained, since the chunk is leaving memory");
    }

    @Test
    void foldChunkStashesCountsEveryThrowingBandMergeAsFailed() {
        CompoundTag chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:lectern", 2, 64, 2),
                blockEntity("minecraft:jukebox", 3, 64, 3));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), THROWING_CONTAINER_SINK,
                THROWING_LECTERN_SINK,
                stash(new BlockPos(1, 64, 1), new CompoundTag()),
                stash(new BlockPos(2, 64, 2), new CompoundTag()),
                stash(new BlockPos(3, 64, 3), discHolder("minecraft:music_disc_cat")));

        assertEquals(2, tally.failed(), "both throwing band merges are counted, not just the first");
        assertEquals(1, tally.merged(), "and the holder fold beside them still lands");
    }

    @Test
    void foldChunkStashesLeavesAnotherChunksEntriesForItsOwnFlush() {
        CompoundTag chunkTag = chunkTagWith(blockEntity("minecraft:chest", 1, 64, 1));
        Map<BlockPos, CompoundTag> containers = stash(new BlockPos(100, 64, 100),
                itemsHolder("minecraft:diamond"));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), containerSink, lecternSink,
                containers, Map.of(), Map.of());

        assertEquals(0, tally.merged());
        assertFalse(containers.isEmpty(), "a holder in another chunk waits for that chunk's own flush");
    }

    @Test
    void foldResidualHoldersSumsBothMergesOntoTheOnDiskChunk() {
        CompoundTag onDisk = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:lectern", 2, 64, 2));

        MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, origin(), containerSink, lecternSink,
                stash(new BlockPos(1, 64, 1), itemsHolder("minecraft:diamond")),
                stash(new BlockPos(2, 64, 2),
                        lecternSink.captureBook(ItemFixtures.writtenBook(2), 1, registries)));

        assertEquals(2, tally.merged(), "both the container and the lectern rewrite land");
        assertEquals(0, tally.failed());
        assertFalse(findByPos(onDisk, 1, 64, 1).getListOrEmpty("Items").isEmpty());
        assertTrue(findByPos(onDisk, 2, 64, 2).contains("Book"));
    }

    @Test
    void foldResidualHoldersCountsEveryThrowingBandMergeAsFailed() {
        CompoundTag onDisk = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:lectern", 2, 64, 2));

        MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, origin(), THROWING_CONTAINER_SINK,
                THROWING_LECTERN_SINK,
                stash(new BlockPos(1, 64, 1), new CompoundTag()),
                stash(new BlockPos(2, 64, 2), new CompoundTag()));

        assertEquals(2, tally.failed(), "both throwing merges are counted, not just the first");
        assertEquals(0, tally.merged());
    }

    @Test
    void landingHolderPositionsNamesOnlyTheHoldersThatWillActuallyLand() {
        CompoundTag chest = blockEntity("minecraft:chest", 1, 64, 1);
        CompoundTag barrel = blockEntity("minecraft:barrel", 2, 64, 2);
        ChunkSnapshotSource snapshot = snapshotOf(chest, barrel);

        CompoundTag matching = itemsHolder("minecraft:diamond");
        matching.putString("wdl_block_entity_id", "minecraft:chest");
        CompoundTag stale = itemsHolder("minecraft:emerald");
        stale.putString("wdl_block_entity_id", "minecraft:chest"); // the block at 2,64,2 is a barrel now
        Map<BlockPos, CompoundTag> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(1, 64, 1), matching);
        holders.put(new BlockPos(2, 64, 2), stale);
        holders.put(new BlockPos(9, 64, 9), itemsHolder("minecraft:dirt")); // no block entity captured here

        List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(snapshot, holders);

        assertEquals(List.of(new BlockPos(1, 64, 1)), landing,
                "a type-changed position and a position with no captured block entity both drop out");
    }

    @Test
    void bookshelfOccupancySkipsWhatIsNotCapturedAsChiseledBookshelf() {
        BlockPos pos = new BlockPos(4, 64, 9);
        BlockState shelf = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(0), true);

        CompoundTag otherType = blockEntity("minecraft:chest", pos.getX(), pos.getY(), pos.getZ());
        assertTrue(ChunkFlushPlan
                .bookshelfOccupancy(SyntheticChunks.withBlockEntityAt(registries, pos, shelf, otherType)).isEmpty(),
                "a block entity of another type is skipped before the block-state resolve");

        CompoundTag noCoordinates = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, pos.getX(), pos.getY(),
                pos.getZ());
        noCoordinates.remove("y");
        assertTrue(ChunkFlushPlan.bookshelfOccupancy(
                SyntheticChunks.withMalformedBlockEntityAt(registries, pos, shelf, noCoordinates)).isEmpty(),
                "a block entity with no int coordinates cannot be keyed and is skipped");

        CompoundTag shelfTag = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, pos.getX(), pos.getY(), pos.getZ());
        assertTrue(ChunkFlushPlan.bookshelfOccupancy(SyntheticChunks.withBlockEntityAt(registries, pos,
                Blocks.STONE.defaultBlockState(), shelfTag)).isEmpty(),
                "and a saved bookshelf id over a block-state that is not one records nothing");
    }

    /** A snapshot whose block-entity list is {@code blockEntities}, with no block states behind them. */
    private static ChunkSnapshotSource snapshotOf(CompoundTag... blockEntities) {
        return SyntheticChunks.fullWithBlockEntities(registries, true, List.of(blockEntities));
    }
}
