// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.chunkTagWith;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.findByPos;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.unhostedBlockEntity;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;

class ChunkMergeTest {
    private static CompoundTag chest(int x, int y, int z, String... itemIds) {
        return container("minecraft:chest", x, y, z, itemIds);
    }

    private static CompoundTag trappedChest(int x, int y, int z, String... itemIds) {
        return container("minecraft:trapped_chest", x, y, z, itemIds);
    }

    private static CompoundTag container(String id, int x, int y, int z, String... itemIds) {
        CompoundTag blockEntity = blockEntity(id, x, y, z);
        blockEntity.put("Items", ItemFixtures.items(itemIds));
        return blockEntity;
    }

    // No vanilla lectern exists at this band, but ChunkMerge still carries the field-based "Book"/"Page"
    // (CapturedBlockField.BOOK) forward for foreign or modded block entities, so a fieldless ender chest stands in as
    // the carrier. A synthetic Book is no producer's output, so a Book-bearing carrier goes through
    // malformedChunkTagWith, not the fidelity-checked chunkTagWith.
    private static CompoundTag lectern(int x, int y, int z, boolean withBook, int page) {
        CompoundTag blockEntity = blockEntity("minecraft:ender_chest", x, y, z);
        if (withBook) {
            blockEntity.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(page + 1)));
            blockEntity.putInt("Page", page);
        }
        return blockEntity;
    }

    private static int itemCount(CompoundTag blockEntity) {
        return blockEntity.get("Items") instanceof ListTag ? ((ListTag) blockEntity.get("Items")).size() : 0;
    }

    private static CompoundTag jukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    private static CompoundTag jukeboxWithDisc(int x, int y, int z, String discId) {
        // JukeboxBlockEntity.save adds only RecordItem to the base block-entity keys, and only when a disc is present;
        // the IsPlaying and tick sidecars are later additions.
        CompoundTag blockEntity = jukebox(x, y, z);
        blockEntity.put("RecordItem", ItemFixtures.itemTag(discId));
        return blockEntity;
    }

    // No vanilla beehive exists at this band, but ChunkMerge still carries the field-based "Bees" occupant list
    // (CapturedBlockField.BEES) for foreign or modded block entities, so a fieldless ender chest stands in as the
    // carrier.
    private static CompoundTag beehive(int x, int y, int z) {
        return blockEntity("minecraft:ender_chest", x, y, z);
    }

    private static CompoundTag beehiveWithBees(int x, int y, int z, int beeCount) {
        CompoundTag blockEntity = beehive(x, y, z);
        int[] ticksInHive = new int[beeCount];
        for (int i = 0; i < beeCount; i++) {
            ticksInHive[i] = 10 * (i + 1);
        }
        blockEntity.put("Bees", BlockEntityFixtures.bees(ticksInHive));
        return blockEntity;
    }

    private static String discId(CompoundTag blockEntity) {
        return blockEntity.get("RecordItem") instanceof CompoundTag
                ? ((CompoundTag) blockEntity.get("RecordItem")).getString("id")
                : "";
    }

    private static int beeCount(CompoundTag blockEntity) {
        return blockEntity.get("Bees") instanceof ListTag ? ((ListTag) blockEntity.get("Bees")).size() : 0;
    }

    /**
     * The match is position plus block-entity type, which a same-type replacement satisfies exactly, so a chest broken
     * and replaced without ever being opened would otherwise inherit the archived contents of the chest that stood
     * there.
     */
    @Test
    void aPositionThatWasReplacedCarriesNothingForward() {
        CompoundTag onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(3, 64, 7)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, replaced),
                "the block on disk is the one the placement replaced, so none of it is this block's");
        assertEquals(0, itemCount(findByPos(fresh, 3, 64, 7)),
                "and the chest now standing there stays as empty as the client saw it");
    }

    @Test
    void aPositionNoPlacementTouchedStillCarriesForward() {
        CompoundTag onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(9, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, replaced),
                "a placement elsewhere in the chunk says nothing about this position");
        assertEquals(1, itemCount(findByPos(fresh, 3, 64, 7)),
                "so a re-walk keeps what an earlier visit archived here");
    }

    @Test
    void anEmptyFreshChestCarriesForwardTheOnDiskContents() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20)); // re-walked, never re-opened: empty

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one container carried forward");
        assertEquals(2, itemCount(findByPos(fresh, 10, 70, 20)), "the prior contents survive the re-walk");
    }

    @Test
    void aRecapturedRichChestKeepsTheFresherContents() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:dirt"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:emerald"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "fresh is already rich, nothing carried back");
        assertEquals(2, itemCount(findByPos(fresh, 10, 70, 20)), "the fresher capture wins");
    }

    @Test
    void freshTerrainIsAuthoritativeForBlockEntitiesRemovedSinceThePriorSession() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith();

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks);
        assertTrue(((ListTag) fresh.getCompound("Level").get("TileEntities")).isEmpty(),
                "a removed container is not resurrected");
    }

    @Test
    void aLecternBookAndPageCarryForwardWhenFreshHasNone() {
        CompoundTag onDisk = BlockEntityFixtures.malformedChunkTagWith(lectern(5, 64, 5, true, 7));
        CompoundTag fresh = chunkTagWith(lectern(5, 64, 5, false, 0));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks);
        CompoundTag merged = findByPos(fresh, 5, 64, 5);
        assertTrue(merged.get("Book") instanceof CompoundTag, "the prior book survives");
        assertEquals(7, (merged.contains("Page") ? merged.getInt("Page") : -1), "and its page");
    }

    @Test
    void aJukeboxDiscCarriesForwardWhenFreshHasNone() {
        CompoundTag onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:music_disc_cat"));
        CompoundTag fresh = chunkTagWith(jukebox(3, 65, 4)); // re-walked, never re-interacted: no disc client-side

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one jukebox disc carried forward");
        CompoundTag merged = findByPos(fresh, 3, 65, 4);
        assertEquals("minecraft:music_disc_cat", discId(merged), "the prior disc survives the re-walk");
    }

    @Test
    void aReInsertedJukeboxDiscKeepsTheFresherDisc() {
        CompoundTag onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:music_disc_cat"));
        CompoundTag fresh = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:music_disc_13"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "a single-slot jukebox has no sibling to clobber; the fresher disc wins");
        assertEquals("minecraft:music_disc_13", discId(findByPos(fresh, 3, 65, 4)), "the re-inserted disc is kept");
    }

    @Test
    void aTypeChangedJukeboxDiscIsNotGhostedIntoTheReplacementChest() {
        CompoundTag onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:music_disc_cat"));
        CompoundTag fresh = chunkTagWith(chest(3, 65, 4));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the disc must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 3, 65, 4).get("RecordItem") instanceof CompoundTag,
                "the replacement chest has no disc");
    }

    @Test
    void aBeehiveOccupantsCarryForwardWhenFreshHasNone() {
        CompoundTag onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 3));
        CompoundTag fresh = chunkTagWith(beehive(8, 72, 1)); // re-walked: the client carries no occupants

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one beehive's occupants carried forward");
        assertEquals(3, beeCount(findByPos(fresh, 8, 72, 1)), "the prior bees survive the re-walk");
    }

    @Test
    void aRePlacedBeehiveKeepsTheFresherOccupants() {
        CompoundTag onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 3));
        CompoundTag fresh = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 1));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the atomically captured fresh occupants win; no partial-list clobber");
        assertEquals(1, beeCount(findByPos(fresh, 8, 72, 1)), "the re-placed hive's occupants are kept");
    }

    @Test
    void anEmptyDiskBeesListIsNotCarried() {
        CompoundTag onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(2, 64, 2, 0));
        CompoundTag fresh = chunkTagWith(beehive(2, 64, 2));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty disk bees list is not carried");
        assertEquals(0, beeCount(findByPos(fresh, 2, 64, 2)), "the re-walked hive stays empty");
    }

    @Test
    void bothEmptyStaysEmptyAndCountsNothing() {
        CompoundTag onDisk = chunkTagWith(chest(1, 64, 1));
        CompoundTag fresh = chunkTagWith(chest(1, 64, 1));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
        assertFalse(findByPos(fresh, 1, 64, 1).get("Items") instanceof ListTag
                && !((ListTag) findByPos(fresh, 1, 64, 1).get("Items")).isEmpty());
    }

    @Test
    void onlyTheMatchingPositionCarriesForward() {
        CompoundTag onDisk = chunkTagWith(
                chest(1, 64, 1, "minecraft:diamond"),
                chest(2, 64, 2, "minecraft:gold_ingot"));
        CompoundTag fresh = chunkTagWith(chest(1, 64, 1), chest(2, 64, 2, "minecraft:emerald"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "only the empty re-captured chest carries forward");
        assertEquals(1, itemCount(findByPos(fresh, 1, 64, 1)), "the prior diamond carried back");
        assertEquals(1, itemCount(findByPos(fresh, 2, 64, 2)), "the rich one kept its fresh emerald");
    }

    @Test
    void aTypeChangedContainerIsNewAndEmptyNotGhosted() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = chunkTagWith(trappedChest(10, 70, 20));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the chest's items must not ghost into a trapped chest at the same position");
        assertFalse(findByPos(fresh, 10, 70, 20).get("Items") instanceof ListTag
                && !((ListTag) findByPos(fresh, 10, 70, 20).get("Items")).isEmpty(),
                "the replacement trapped chest stays empty, not the chest's 27 slots");
    }

    @Test
    void aTypeChangedLecternBookIsNotGhostedIntoTheReplacementChest() {
        CompoundTag onDisk = BlockEntityFixtures.malformedChunkTagWith(lectern(5, 64, 5, true, 7));
        CompoundTag fresh = chunkTagWith(chest(5, 64, 5));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the lectern's book must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 5, 64, 5).get("Book") instanceof CompoundTag, "the replacement chest has no book");
    }

    @Test
    void aRevisitReflectsPlacedBlockEntitiesAndCarriesTheSurvivingContainerForward() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20), trappedChest(11, 70, 20));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "only the surviving chest carries forward");
        assertEquals(1, itemCount(findByPos(fresh, 10, 70, 20)), "the chest keeps its earlier-saved diamond");
        assertFalse(findByPos(fresh, 11, 70, 20).get("Items") instanceof ListTag
                && !((ListTag) findByPos(fresh, 11, 70, 20).get("Items")).isEmpty(),
                "the newly placed trapped chest is present and empty (the revisit change is reflected)");
    }

    @Test
    void onDiskLightNeverCarriesForward() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        onDisk.putBoolean("isLightOn", true);
        ListTag onDiskSections = new ListTag();
        CompoundTag onDiskSection = new CompoundTag();
        onDiskSection.putByte("Y", (byte) 0);
        onDiskSection.putByteArray("BlockLight", new byte[2048]);
        onDiskSections.add(onDiskSection);
        onDisk.put("sections", onDiskSections);

        CompoundTag fresh = chunkTagWith(chest(10, 70, 20));
        ListTag freshSections = new ListTag();
        CompoundTag freshSection = new CompoundTag();
        freshSection.putByte("Y", (byte) 0);
        freshSections.add(freshSection);
        fresh.put("sections", freshSections);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the chest must carry forward, proving merge ran its real path");
        assertFalse(fresh.getBoolean("isLightOn"), "on-disk isLightOn must not carry onto a gate-false fresh chunk");
        assertTrue(fresh.getList("sections", 10).stream().map(t -> (CompoundTag) t)
                .noneMatch(section -> section.contains("BlockLight", 7)
                        || section.contains("SkyLight", 7)),
                "on-disk light layers must not carry onto fresh sections");
    }

    @Test
    void aChunkWithNoFreshBlockEntitiesDoesNothing() {
        CompoundTag onDisk = chunkTagWith(chest(1, 64, 1, "minecraft:diamond"));
        CompoundTag fresh = new CompoundTag();

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
    }

    /**
     * A chiseled bookshelf's saved tag. No block on this band hosts the type, so it has no producer to take a shape
     * from.
     */
    private static CompoundTag bookshelf(int x, int y, int z, int... slots) {
        CompoundTag blockEntity = unhostedBlockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, x, y, z);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        blockEntity.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return blockEntity;
    }

    private static CompoundTag bookshelfChunk(CompoundTag... blockEntities) {
        return BlockEntityFixtures.malformedChunkTagWith(blockEntities);
    }

    /**
     * The slots an Items list names, in list order and WITH duplicates. A set would hide the one corruption this axis
     * can produce: two entries for one slot, where vanilla's load does a last-write-wins set and the stale entry
     * therefore beats the fresher one.
     */
    private static List<Integer> slotsOf(CompoundTag blockEntity) {
        List<Integer> slots = new ArrayList<>();
        if (blockEntity.get("Items") instanceof ListTag) {
            ListTag items = (ListTag) blockEntity.get("Items");
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = (CompoundTag) items.get(i);
                slots.add((int) (entry.contains("Slot") ? entry.getByte("Slot") : (byte) -1));
            }
        }
        return slots;
    }

    private static List<Integer> sortedSlotsOf(CompoundTag blockEntity) {
        List<Integer> slots = new ArrayList<>(slotsOf(blockEntity));
        Collections.sort(slots);
        return slots;
    }

    private static CompoundTag brewingStand(int x, int y, int z, short brewTime, byte fuel) {
        CompoundTag blockEntity = blockEntity("minecraft:brewing_stand", x, y, z);
        blockEntity.putShort("BrewTime", brewTime);
        blockEntity.putByte("Fuel", fuel);
        return blockEntity;
    }

    /** A brewing stand as the client re-captures one it never opened: the keys present, at their defaults. */
    private static CompoundTag freshBrewingStand(int x, int y, int z) {
        return brewingStand(x, y, z, (short) 0, (byte) 0);
    }

    @Test
    void aBookshelfInsertUnionsWithTheBooksAlreadyOnDisk() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the shelf carried forward");
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books already on disk survive beside the fourth");
    }

    @Test
    void aBookshelfSlotCapturedTwiceKeepsTheFresherBook() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1));
        CompoundTag freshShelf = bookshelf(4, 64, 9);
        freshShelf.put("Items", ItemFixtures.itemsAtSlots(new int[] { 1 }, "minecraft:enchanted_book"));
        CompoundTag fresh = bookshelfChunk(freshShelf);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "slot 0 carried forward");
        assertEquals(ImmutableList.of(0, 1), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "both slots present, and exactly once each: a duplicated slot would let the stale disk book "
                        + "overwrite the fresher one, since vanilla's load is a last-write-wins set");
        ListTag merged = (ListTag) findByPos(fresh, 4, 64, 9).get("Items");
        assertTrue(
                merged.stream().map(tag -> (CompoundTag) tag)
                        .anyMatch(entry -> (entry.contains("Slot") ? entry.getByte("Slot") : (byte) -1) == 1
                                && "minecraft:enchanted_book".equals(entry.getString("id"))),
                "the slot captured this session keeps the fresher book");
    }

    @Test
    void anEmptyFreshBookshelfStillCarriesTheWholeDiskList() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9)); // re-walked, no insert this pass

        assertEquals(1, ChunkMerge.merge(onDisk, fresh));
        assertEquals(ImmutableList.of(0, 1, 2), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "a plain re-walk loses nothing");
    }

    @Test
    void anOnDiskBookshelfHoldingNoBooksCarriesNothingAndCountsNothing() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty on-disk shelf carries nothing");
        assertEquals(Collections.singletonList(3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "and the fresh insert stands alone");
    }

    @Test
    void aBookshelfOpenedThisWriteStillUnionsTheBooksAlreadyOnDisk() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(4, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "the shelf carries forward even though this write captured a slot of it");
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books an earlier flush saved survive beside the fourth");
    }

    @Test
    void aFreshBlockEntityWithNoCompletePositionCarriesNothing() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag positionless = chest(10, 70, 20);
        positionless.remove("y");
        CompoundTag fresh = BlockEntityFixtures.malformedChunkTagWith(positionless);

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "no position, no carry-forward");
        assertEquals(0, itemCount(((ListTag) fresh.getCompound("Level").get("TileEntities")).getCompound(0)),
                "and the on-disk contents are not written onto it");
    }

    @Test
    void aPartlyEmptiedChestDoesNotResurrectTheItemsTakenOutOfIt() {
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20, "minecraft:dirt"));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "nothing carried back into a re-opened chest");
        assertEquals(1, itemCount(findByPos(fresh, 10, 70, 20)), "only what the re-open saw is kept");
    }

    @Test
    void aChestOpenedThisWriteAndFoundEmptyIsWrittenEmpty() {
        // The whole difficulty is that both fresh sides are an empty "Items": an opened chest and a re-walked one
        // serialize identically, and only the position set says the emptiness was seen rather than never captured.
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(10, 70, 20)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "an opened menu is ground truth for every slot, so nothing carries back over it");
        CompoundTag merged = findByPos(fresh, 10, 70, 20);
        assertTrue(merged.get("Items") instanceof ListTag && ((ListTag) merged.get("Items")).isEmpty(),
                "the chest is written with the present-but-empty list vanilla's own chest writer emits, so the "
                        + "items the player watched leave stay gone");
    }

    @Test
    void anOpenAtOnePositionSaysNothingAboutItsNeighbor() {
        CompoundTag onDisk = chunkTagWith(
                chest(1, 64, 1, "minecraft:diamond"),
                chest(2, 64, 2, "minecraft:emerald"));
        CompoundTag fresh = chunkTagWith(chest(1, 64, 1), chest(2, 64, 2));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(1, 64, 1)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "one carry-forward, and it is the neighbor's rather than the opened chest's");
        assertEquals(0, itemCount(findByPos(fresh, 1, 64, 1)), "the chest this write saw empty stays empty");
        assertEquals(1, itemCount(findByPos(fresh, 2, 64, 2)),
                "and the chest it never opened keeps what an earlier write archived");
    }

    @Test
    void aReOpenedBrewingStandKeepsItsFresherState() {
        CompoundTag onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        CompoundTag fresh = chunkTagWith(brewingStand(6, 64, 6, (short) 60, (byte) 0));
        LongSet reopened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(6, 64, 6)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), reopened, LongSets.EMPTY_SET),
                "the fresh open is authoritative");
        CompoundTag merged = findByPos(fresh, 6, 64, 6);
        assertEquals((short) 60, merged.getShort("BrewTime"));
        assertEquals((byte) 0, (merged.contains("Fuel") ? merged.getByte("Fuel") : (byte) -1),
                "a re-open that saw the stand out of fuel keeps that, rather than inheriting the stale fuel");
    }

    @Test
    void aBrewingStandKeepsItsBrewTimeAndFuelAcrossAnyRewrite() {
        CompoundTag onDisk = chunkTagWith(brewingStand(7, 64, 7, (short) 220, (byte) 12));
        CompoundTag fresh = chunkTagWith(freshBrewingStand(7, 64, 7));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh));
        CompoundTag merged = findByPos(fresh, 7, 64, 7);
        assertEquals((short) 220, (merged.contains("BrewTime") ? merged.getShort("BrewTime") : (short) -1),
                "a mid-brew stand does not restart");
        assertEquals((byte) 12, (merged.contains("Fuel") ? merged.getByte("Fuel") : (byte) -1),
                "and keeps its blaze powder");
    }

    @Test
    void aBookTakenOutBetweenVisitsIsNotWrittenBackIntoAnEmptySlot() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(4, 64, 9).asLong(), 0b1110); // slot 0 emptied since; 1, 2 and 3 occupied

        int mergeBacks = ChunkMerge.merge(onDisk, fresh, occupancy, LongSets.EMPTY_SET, LongSets.EMPTY_SET);

        assertEquals(1, mergeBacks, "the shelf still carries the slots that are still occupied");
        assertEquals(ImmutableList.of(1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "slot 0 is dropped because the block-state says it is empty");
    }

    @Test
    void aBookshelfWithNoRecordedOccupancyKeepsEveryOnDiskSlot() {
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        assertEquals(1,
                ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, LongSets.EMPTY_SET));
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)));
    }

    @Test
    void aBookshelfNotNamedByTheOccupancyMapKeepsEveryOnDiskSlot() {
        // The unknown-occupancy fallback has to survive a map that names OTHER positions.
        CompoundTag onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(11, 70, 2).asLong(), 0b1);

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, occupancy, LongSets.EMPTY_SET, LongSets.EMPTY_SET));
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "an unrecorded position falls back to keeping every slot, not to keeping none");
    }

    @Test
    void aFreshStateCaptureIsNotOverwrittenEvenWhenItsPositionWasNotNamed() {
        // The defensive half of the scalar carry. Both state keys are non-default here, so nothing at all is left for
        // the carry to do and the count says so; with one of them at its default that key carries, and the count could
        // not tell the two halves apart.
        CompoundTag onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        CompoundTag fresh = chunkTagWith(brewingStand(6, 64, 6, (short) 180, (byte) 7));

        assertEquals(0,
                ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, LongSets.EMPTY_SET),
                "a block entity whose every field is its own capture is not a carry-forward");

        CompoundTag merged = findByPos(fresh, 6, 64, 6);
        assertEquals((short) 180, merged.getShort("BrewTime"), "the fresh non-default capture stands");
        assertEquals((byte) 7, (merged.contains("Fuel") ? merged.getByte("Fuel") : (byte) -1),
                "and so does the value it captured beside it");
    }

    @Test
    void hasCapturedContentRejectsOpenTimeStateAlone() {
        // A brewing stand carries state on every visit once opened; counting it as content would tell the resume
        // outline the container was recovered when nothing of its contents was.
        assertFalse(ChunkMerge.hasCapturedContent(brewingStand(0, 0, 0, (short) 20, (byte) 3)),
                "a brewing stand's ticks and fuel are not captured content");
    }

    @Test
    void hasCapturedContentRecognizesFilledContainerAndLecternBook() {
        assertTrue(ChunkMerge.hasCapturedContent(chest(0, 0, 0, "minecraft:diamond")), "a filled chest is content");
        assertTrue(ChunkMerge.hasCapturedContent(lectern(0, 0, 0, true, 3)), "a lectern with a book is content");
    }

    @Test
    void hasCapturedContentRejectsEmptyContainerOrBooklessLectern() {
        assertFalse(ChunkMerge.hasCapturedContent(chest(0, 0, 0)), "an empty chest is not content");
        assertFalse(ChunkMerge.hasCapturedContent(lectern(0, 0, 0, false, 0)), "a bookless lectern is not content");
    }

    @Test
    void hasCapturedContentRecognizesJukeboxDiscAndBeehiveBees() {
        assertTrue(ChunkMerge.hasCapturedContent(jukeboxWithDisc(0, 0, 0, "minecraft:music_disc_cat")),
                "a jukebox with a disc is content");
        assertTrue(ChunkMerge.hasCapturedContent(beehiveWithBees(0, 0, 0, 2)), "a beehive with bees is content");
    }

    @Test
    void hasCapturedContentRejectsEmptyJukeboxOrBeehive() {
        assertFalse(ChunkMerge.hasCapturedContent(jukebox(0, 0, 0)), "an empty jukebox is not content");
        assertFalse(ChunkMerge.hasCapturedContent(beehive(0, 0, 0)), "an empty beehive is not content");
        assertFalse(ChunkMerge.hasCapturedContent(beehiveWithBees(0, 0, 0, 0)),
                "a beehive whose bees list is present but empty is not content");
    }
}
