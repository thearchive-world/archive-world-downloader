// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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
    private final ContainerSink containerSink = new ContainerSinkImpl();
    private final LecternSink lecternSink = new LecternSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** Built on demand, never as a static initializer: ChunkPos static init needs the vanilla bootstrap. */
    private static ChunkPos origin() {
        return new ChunkPos(0, 0);
    }

    private NBTTagCompound itemsHolder(String itemId) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, ItemFixtures.stack(itemId));
        return containerSink.captureItems(items);
    }

    private static NBTTagCompound discHolder(String discId) {
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("RecordItem", ItemFixtures.itemTag(discId));
        return holder;
    }

    private static Map<BlockPos, NBTTagCompound> stash(BlockPos pos, NBTTagCompound holder) {
        Map<BlockPos, NBTTagCompound> stash = new LinkedHashMap<>();
        stash.put(pos, holder);
        return stash;
    }

    /** A sink whose merge throws, the band-merge failure the fold has to isolate and count. */
    private static final ContainerSink THROWING_CONTAINER_SINK = new ContainerSink() {
        @Override
        public NBTTagCompound captureItems(NonNullList<ItemStack> items) {
            throw new AssertionError("the failure path never serializes items");
        }

        @Override
        public NBTTagCompound merge(NBTTagCompound blockEntityTag, NBTTagCompound capturedItemsHolder) {
            throw new IllegalStateException("a band container merge blew up");
        }
    };

    private static final LecternSink THROWING_LECTERN_SINK = new LecternSink() {
        @Override
        public NBTTagCompound captureBook(ItemStack book, int page) {
            throw new AssertionError("the failure path never serializes a book");
        }

        @Override
        public NBTTagCompound merge(NBTTagCompound blockEntityTag, NBTTagCompound capturedBookHolder) {
            throw new IllegalStateException("a band lectern merge blew up");
        }
    };

    /**
     * The replaced positions are held per dimension and handed to the writer per chunk, so the narrowing is both what
     * detaches them from the live set and what keeps one chunk's placements out of another's merge.
     */
    @Test
    void theReplacedPositionsHandedOverAreThisChunksOwn() {
        BlockPos inside = new BlockPos(3, 64, 7);
        BlockPos elsewhere = new BlockPos(300, 64, 700);
        LongOpenHashSet replaced = new LongOpenHashSet();
        replaced.add(inside.toLong());
        replaced.add(elsewhere.toLong());

        LongSet forChunk = ChunkFlushPlan.replacedIn(new ChunkPos(inside), replaced);

        assertTrue(forChunk.contains(inside.toLong()), "a placement in this chunk reaches this chunk's merge");
        assertFalse(forChunk.contains(elsewhere.toLong()), "and one in another chunk does not");
    }

    @Test
    void theReadMergeHonorsTheOpenTimePositionsItWasBuiltWith() {
        // Both fresh sides read as the client defaults, which is the whole difficulty: only the position set
        // says whether that default was captured from a re-opened menu or never captured at all.
        NBTTagCompound onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        NBTTagCompound freshReopened = chunkTagWith(brewingStand(6, 64, 6, (short) 0, (byte) 0));
        NBTTagCompound freshRewalked = chunkTagWith(brewingStand(6, 64, 6, (short) 0, (byte) 0));
        LongSet openTimeCaptured = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(6, 64, 6)));

        int withPosition = ChunkFlushPlan.readMerge(ChunkMerge.occupancyMap(), openTimeCaptured, LongSets.EMPTY_SET)
                .merge(onDisk.copy(), freshReopened);
        int withoutPosition = ChunkFlushPlan
                .readMerge(ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, LongSets.EMPTY_SET)
                .merge(onDisk.copy(), freshRewalked);

        assertEquals(0, withPosition,
                "a brewing stand re-opened this session captured its own state, nothing carries back");
        assertEquals((short) 0,
                findByPos(freshReopened, 6, 64, 6).getShort("BrewTime"),
                "so the state the re-open captured survives");
        assertEquals(1, withoutPosition, "and without the position the prior state does carry forward");
        assertEquals((short) 220,
                findByPos(freshRewalked, 6, 64, 6).getShort("BrewTime"),
                "which is what proves the set is read rather than ignored");
    }

    @Test
    void theComposedReadMergeDerivesTheOpenTimePositionsFromTheLandingHolders() {
        BlockPos pos = new BlockPos(6, 64, 6);
        ChunkSnapshotSource snapshot = snapshotOf(blockEntity("minecraft:brewing_stand", 6, 64, 6));
        NBTTagCompound onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        NBTTagCompound freshReopened = chunkTagWith(brewingStand(6, 64, 6, (short) 0, (byte) 0));
        NBTTagCompound freshRewalked = chunkTagWith(brewingStand(6, 64, 6, (short) 0, (byte) 0));

        int landed = ChunkFlushPlan.readMerge(snapshot, ImmutableList.of(pos), LongSets.EMPTY_SET)
                .merge(onDisk.copy(), freshReopened);
        int none = ChunkFlushPlan.readMerge(snapshot, ImmutableList.of(), LongSets.EMPTY_SET).merge(onDisk.copy(),
                freshRewalked);

        assertEquals(0, landed, "a position named as landing captured its own state, so nothing carries back");
        assertEquals(1, none, "and one not named carries the prior state forward");
        assertEquals((short) 220,
                findByPos(freshRewalked, 6, 64, 6).getShort("BrewTime"),
                "which is what proves the landing list reaches the merge rather than being dropped");
    }

    @Test
    void landingHolderPositionsIsWhatTheComposedReadMergeShouldBeGiven() {
        // The list handed to the composed merge has to be the LANDING holders, not every drained one: a holder
        // the fold drops writes no state, and calling it captured would blank what an earlier visit saved.
        NBTTagCompound brewingStandTag = blockEntity("minecraft:brewing_stand", 6, 64, 6);
        ChunkSnapshotSource snapshot = snapshotOf(brewingStandTag);
        Map<BlockPos, NBTTagCompound> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(6, 64, 6), itemsHolder("minecraft:diamond"));
        holders.put(new BlockPos(9, 64, 9), itemsHolder("minecraft:dirt")); // no block entity captured here

        List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(snapshot, holders);

        assertEquals(ImmutableList.of(new BlockPos(6, 64, 6)), landing,
                "the position with no captured block entity is not a landing holder");
    }

    /** A brewing stand carrying the two state keys vanilla writes unconditionally, at the given values. */
    private static NBTTagCompound brewingStand(int x, int y, int z, short brewTime, byte fuel) {
        NBTTagCompound tag = blockEntity("minecraft:brewing_stand", x, y, z);
        tag.setShort("BrewTime", brewTime);
        tag.setByte("Fuel", fuel);
        return tag;
    }

    // No vanilla lectern exists at this band, so the lectern-stash fold is exercised against a fieldless ender chest
    // standing in as the carrier the LecternSink book merge writes "Book"/"Page" onto; the fold wiring is what is
    // under test, not a real lectern.
    @Test
    void foldChunkStashesMergesEveryStashOntoItsOwnBlockEntity() {
        NBTTagCompound chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:ender_chest", 2, 64, 2),
                blockEntity("minecraft:jukebox", 3, 64, 3));

        Map<BlockPos, NBTTagCompound> containers = stash(new BlockPos(1, 64, 1), itemsHolder("minecraft:diamond"));
        Map<BlockPos, NBTTagCompound> lecterns = stash(new BlockPos(2, 64, 2),
                lecternSink.captureBook(ItemFixtures.writtenBook(2), 1));
        Map<BlockPos, NBTTagCompound> holders = stash(new BlockPos(3, 64, 3), discHolder("minecraft:record_cat"));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), containerSink, lecternSink,
                containers, lecterns, holders);

        assertEquals(3, tally.merged(), "each of the three stashes folded its own block entity");
        assertEquals(0, tally.failed());
        assertFalse(findByPos(chunkTag, 1, 64, 1).getTagList("Items", 10).hasNoTags(),
                "the chest gained its items");
        assertTrue(findByPos(chunkTag, 2, 64, 2).hasKey("Book"), "the lectern gained its book");
        assertTrue(findByPos(chunkTag, 3, 64, 3).hasKey("RecordItem"), "the jukebox gained its disc");
        assertTrue(containers.isEmpty() && lecterns.isEmpty() && holders.isEmpty(),
                "and every stash is drained, since the chunk is leaving memory");
    }

    @Test
    void foldChunkStashesCountsEveryThrowingBandMergeAsFailed() {
        NBTTagCompound chunkTag = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:ender_chest", 2, 64, 2),
                blockEntity("minecraft:jukebox", 3, 64, 3));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), THROWING_CONTAINER_SINK,
                THROWING_LECTERN_SINK,
                stash(new BlockPos(1, 64, 1), new NBTTagCompound()),
                stash(new BlockPos(2, 64, 2), new NBTTagCompound()),
                stash(new BlockPos(3, 64, 3), discHolder("minecraft:record_cat")));

        assertEquals(2, tally.failed(), "both throwing band merges are counted, not just the first");
        assertEquals(1, tally.merged(), "and the holder fold beside them still lands");
    }

    @Test
    void foldChunkStashesLeavesAnotherChunksEntriesForItsOwnFlush() {
        NBTTagCompound chunkTag = chunkTagWith(blockEntity("minecraft:chest", 1, 64, 1));
        Map<BlockPos, NBTTagCompound> containers = stash(new BlockPos(100, 64, 100),
                itemsHolder("minecraft:diamond"));

        MergeTally tally = ChunkFlushPlan.foldChunkStashes(chunkTag, origin(), containerSink, lecternSink,
                containers, ImmutableMap.of(), ImmutableMap.of());

        assertEquals(0, tally.merged());
        assertFalse(containers.isEmpty(), "a holder in another chunk waits for that chunk's own flush");
    }

    @Test
    void foldResidualHoldersSumsBothMergesOntoTheOnDiskChunk() {
        NBTTagCompound onDisk = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:ender_chest", 2, 64, 2));

        MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, origin(), containerSink, lecternSink,
                stash(new BlockPos(1, 64, 1), itemsHolder("minecraft:diamond")),
                stash(new BlockPos(2, 64, 2),
                        lecternSink.captureBook(ItemFixtures.writtenBook(2), 1)));

        assertEquals(2, tally.merged(), "both the container and the lectern rewrite land");
        assertEquals(0, tally.failed());
        assertFalse(findByPos(onDisk, 1, 64, 1).getTagList("Items", 10).hasNoTags());
        assertTrue(findByPos(onDisk, 2, 64, 2).hasKey("Book"));
    }

    @Test
    void foldResidualHoldersCountsEveryThrowingBandMergeAsFailed() {
        NBTTagCompound onDisk = chunkTagWith(
                blockEntity("minecraft:chest", 1, 64, 1),
                blockEntity("minecraft:ender_chest", 2, 64, 2));

        MergeTally tally = ChunkFlushPlan.foldResidualHolders(onDisk, origin(), THROWING_CONTAINER_SINK,
                THROWING_LECTERN_SINK,
                stash(new BlockPos(1, 64, 1), new NBTTagCompound()),
                stash(new BlockPos(2, 64, 2), new NBTTagCompound()));

        assertEquals(2, tally.failed(), "both throwing merges are counted, not just the first");
        assertEquals(0, tally.merged());
    }

    @Test
    void landingHolderPositionsNamesOnlyTheHoldersThatWillActuallyLand() {
        NBTTagCompound chest = blockEntity("minecraft:chest", 1, 64, 1);
        NBTTagCompound shulkerBox = blockEntity("minecraft:shulker_box", 2, 64, 2);
        ChunkSnapshotSource snapshot = snapshotOf(chest, shulkerBox);

        NBTTagCompound matching = itemsHolder("minecraft:diamond");
        matching.setString("wdl_block_entity_id", "minecraft:chest");
        NBTTagCompound stale = itemsHolder("minecraft:emerald");
        stale.setString("wdl_block_entity_id", "minecraft:chest"); // the block at 2,64,2 is a shulker box now
        Map<BlockPos, NBTTagCompound> holders = new LinkedHashMap<>();
        holders.put(new BlockPos(1, 64, 1), matching);
        holders.put(new BlockPos(2, 64, 2), stale);
        holders.put(new BlockPos(9, 64, 9), itemsHolder("minecraft:dirt")); // no block entity captured here

        List<BlockPos> landing = ChunkFlushPlan.landingHolderPositions(snapshot, holders);

        assertEquals(ImmutableList.of(new BlockPos(1, 64, 1)), landing,
                "a type-changed position and a position with no captured block entity both drop out");
    }

    /** A snapshot whose block-entity list is {@code blockEntities}, with no block states behind them. */
    private static ChunkSnapshotSource snapshotOf(NBTTagCompound... blockEntities) {
        return SyntheticChunks.fullWithBlockEntities(true, ImmutableList.copyOf(blockEntities));
    }
}
