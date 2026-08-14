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
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.SyntheticChunks;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the chunk read-merge: {@link ChunkMerge} carries forward every interaction-captured datum (a
 * block container's {@code "Items"}, a lectern's {@code "Book"}/{@code "Page"}, a jukebox disc's {@code "RecordItem"},
 * a beehive's {@code "bees"}) from the on-disk chunk into a freshly re-captured one, preferring non-empty wherever the
 * write captured nothing of its own there, so a re-walk live-updates the terrain without wiping a chest an earlier
 * write archived, while a container this write captured is left exactly as that capture saw it. Pure
 * {@code CompoundTag} in/out, band-agnostic over the post-1.18 {@code "block_entities"} layout, matched by
 * {@code x/y/z}.
 */
class ChunkMergeTest {
    private static CompoundTag chest(int x, int y, int z, String... itemIds) {
        return container("minecraft:chest", x, y, z, itemIds);
    }

    private static CompoundTag barrel(int x, int y, int z, String... itemIds) {
        return container("minecraft:barrel", x, y, z, itemIds);
    }

    private static CompoundTag container(String id, int x, int y, int z, String... itemIds) {
        CompoundTag blockEntity = blockEntity(id, x, y, z);
        blockEntity.put("Items", ItemFixtures.items(itemIds));
        return blockEntity;
    }

    private static CompoundTag lectern(int x, int y, int z, boolean withBook, int page) {
        CompoundTag blockEntity = blockEntity("minecraft:lectern", x, y, z);
        if (withBook) {
            blockEntity.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(page + 1)));
            blockEntity.putInt("Page", page);
        }
        return blockEntity;
    }

    private static int itemCount(CompoundTag blockEntity) {
        return blockEntity.get("Items") instanceof ListTag items ? items.size() : 0;
    }

    private static CompoundTag jukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    private static CompoundTag jukeboxWithDisc(int x, int y, int z, String discId) {
        CompoundTag blockEntity = jukebox(x, y, z);
        blockEntity.put("RecordItem", ItemFixtures.itemTag(discId));
        blockEntity.putBoolean("IsPlaying", true);
        return blockEntity;
    }

    private static CompoundTag beehive(int x, int y, int z) {
        return blockEntity("minecraft:beehive", x, y, z);
    }

    private static CompoundTag beehiveWithBees(int x, int y, int z, int beeCount) {
        CompoundTag blockEntity = beehive(x, y, z);
        int[] ticksInHive = new int[beeCount];
        for (int i = 0; i < beeCount; i++) {
            ticksInHive[i] = 10 * (i + 1);
        }
        blockEntity.put("bees", BlockEntityFixtures.bees(ticksInHive));
        return blockEntity;
    }

    private static String discId(CompoundTag blockEntity) {
        return blockEntity.get("RecordItem") instanceof CompoundTag record ? record.getString("id") : "";
    }

    private static int beeCount(CompoundTag blockEntity) {
        return blockEntity.get("bees") instanceof ListTag bees ? bees.size() : 0;
    }

    /**
     * The on-disk carry-forward is the last store a same-position block replacement makes stale, and the one a
     * placement's in-memory drop cannot reach. The match is position plus block-entity type, which a same-type
     * replacement satisfies exactly, so a chest broken and replaced without ever being opened would otherwise inherit
     * the archived contents of the chest that stood there.
     */
    @Test
    void aPositionThatWasReplacedCarriesNothingForward() {
        CompoundTag onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(List.of(new BlockPos(3, 64, 7)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSet.of(), replaced),
                "the block on disk is the one the placement replaced, so none of it is this block's");
        assertEquals(0, itemCount(findByPos(fresh, 3, 64, 7)),
                "and the chest now standing there stays as empty as the client saw it");
    }

    /** The other half: an untouched position still carries forward, so the guard is not a blanket refusal. */
    @Test
    void aPositionNoPlacementTouchedStillCarriesForward() {
        CompoundTag onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(List.of(new BlockPos(9, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSet.of(), replaced),
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
        CompoundTag fresh = chunkTagWith(); // the chest was broken since; fresh terrain has no block entity

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks);
        assertTrue(((ListTag) fresh.get("block_entities")).isEmpty(), "a removed container is not resurrected");
    }

    @Test
    void aLecternBookAndPageCarryForwardWhenFreshHasNone() {
        CompoundTag onDisk = chunkTagWith(lectern(5, 64, 5, true, 7));
        CompoundTag fresh = chunkTagWith(lectern(5, 64, 5, false, 0));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks);
        CompoundTag merged = findByPos(fresh, 5, 64, 5);
        assertTrue(merged.get("Book") instanceof CompoundTag, "the prior book survives");
        assertEquals(7, (merged.contains("Page") ? merged.getInt("Page") : -1), "and its page");
    }

    @Test
    void aJukeboxDiscAndItsPlayingStateCarryForwardWhenFreshHasNone() {
        CompoundTag onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:music_disc_cat"));
        CompoundTag fresh = chunkTagWith(jukebox(3, 65, 4)); // re-walked, never re-interacted: no disc client-side

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one jukebox disc carried forward");
        CompoundTag merged = findByPos(fresh, 3, 65, 4);
        assertEquals("minecraft:music_disc_cat", discId(merged), "the prior disc survives the re-walk");
        assertTrue(merged.getBoolean("IsPlaying"), "and its playing state");
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
        CompoundTag fresh = chunkTagWith(chest(3, 65, 4)); // a chest now stands where the jukebox was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the disc must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 3, 65, 4).get("RecordItem") instanceof CompoundTag,
                "the replacement chest has no disc");
    }

    @Test
    void aBeehiveOccupantsCarryForwardWhenFreshHasNone() {
        CompoundTag onDisk = chunkTagWith(beehiveWithBees(8, 72, 1, 3));
        CompoundTag fresh = chunkTagWith(beehive(8, 72, 1)); // re-walked: the client carries no occupants

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one beehive's occupants carried forward");
        assertEquals(3, beeCount(findByPos(fresh, 8, 72, 1)), "the prior bees survive the re-walk");
    }

    @Test
    void aRePlacedBeehiveKeepsTheFresherOccupants() {
        CompoundTag onDisk = chunkTagWith(beehiveWithBees(8, 72, 1, 3));
        CompoundTag fresh = chunkTagWith(beehiveWithBees(8, 72, 1, 1)); // a populated hive placed this session

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the atomically captured fresh occupants win; no partial-list clobber");
        assertEquals(1, beeCount(findByPos(fresh, 8, 72, 1)), "the re-placed hive's occupants are kept");
    }

    @Test
    void anEmptyDiskBeesListIsNotCarried() {
        // present-but-empty "bees" is vanilla's emptied-hive state, distinct from the key being absent
        CompoundTag onDisk = chunkTagWith(beehiveWithBees(2, 64, 2, 0));
        CompoundTag fresh = chunkTagWith(beehive(2, 64, 2));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty disk bees list is not carried");
        assertEquals(0, beeCount(findByPos(fresh, 2, 64, 2)), "the re-walked hive stays empty");
    }

    @Test
    void bothEmptyStaysEmptyAndCountsNothing() {
        CompoundTag onDisk = chunkTagWith(chest(1, 64, 1));
        CompoundTag fresh = chunkTagWith(chest(1, 64, 1));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
        assertFalse(findByPos(fresh, 1, 64, 1).get("Items") instanceof ListTag list && !list.isEmpty());
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
        CompoundTag fresh = chunkTagWith(barrel(10, 70, 20)); // a barrel now stands where the chest was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the chest's items must not ghost into a barrel at the same position");
        assertFalse(findByPos(fresh, 10, 70, 20).get("Items") instanceof ListTag list && !list.isEmpty(),
                "the replacement barrel stays empty, not the chest's 27 slots");
    }

    @Test
    void aTypeChangedLecternBookIsNotGhostedIntoTheReplacementChest() {
        CompoundTag onDisk = chunkTagWith(lectern(5, 64, 5, true, 7));
        CompoundTag fresh = chunkTagWith(chest(5, 64, 5)); // a chest now stands where the lectern was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the lectern's book must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 5, 64, 5).get("Book") instanceof CompoundTag, "the replacement chest has no book");
    }

    @Test
    void aRevisitReflectsPlacedBlockEntitiesAndCarriesTheSurvivingContainerForward() {
        // A revisit re-flush drives the merge from the fresh capture: a barrel placed since the first flush is
        // present (the change lands), and the chest that survived carries its earlier-saved contents forward.
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20), barrel(11, 70, 20));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "only the surviving chest carries forward");
        assertEquals(1, itemCount(findByPos(fresh, 10, 70, 20)), "the chest keeps its earlier-saved diamond");
        assertFalse(findByPos(fresh, 11, 70, 20).get("Items") instanceof ListTag list && !list.isEmpty(),
                "the newly placed barrel is present and empty (the revisit change is reflected)");
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

        // The fresh chunk carries its own section at the same Y with no light layers on it. Without a section
        // there the light assertion below iterates nothing and holds for any merge at all.
        CompoundTag fresh = chunkTagWith(chest(10, 70, 20)); // re-walked, gate-false: no light tags
        ListTag freshSections = new ListTag();
        CompoundTag freshSection = new CompoundTag();
        freshSection.putByte("Y", (byte) 0);
        freshSections.add(freshSection);
        fresh.put("sections", freshSections);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the chest must carry forward, proving merge ran its real path");
        assertFalse(fresh.getBoolean("isLightOn"),
                "on-disk isLightOn must not carry onto a gate-false fresh chunk");
        assertTrue(fresh.getList("sections", Tag.TAG_COMPOUND).stream().map(t -> (CompoundTag) t)
                .noneMatch(section -> section.contains("BlockLight", Tag.TAG_BYTE_ARRAY)
                        || section.contains("SkyLight", Tag.TAG_BYTE_ARRAY)),
                "on-disk light layers must not carry onto fresh sections");
    }

    @Test
    void aChunkWithNoFreshBlockEntitiesDoesNothing() {
        CompoundTag onDisk = chunkTagWith(chest(1, 64, 1, "minecraft:diamond"));
        CompoundTag fresh = new CompoundTag(); // no block_entities key at all

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
    }

    private static CompoundTag bookshelf(int x, int y, int z, int... slots) {
        CompoundTag blockEntity = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, x, y, z);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        blockEntity.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return blockEntity;
    }

    /**
     * The slots an Items list names, in list order and WITH duplicates. A set would hide the one corruption this axis
     * can produce: two entries for one slot, where vanilla's load does a last-write-wins set and the stale entry
     * therefore beats the fresher one.
     */
    private static List<Integer> slotsOf(CompoundTag blockEntity) {
        List<Integer> slots = new ArrayList<>();
        if (blockEntity.get("Items") instanceof ListTag items) {
            for (int i = 0; i < items.size(); i++) {
                slots.add((int) (((CompoundTag) items.get(i)).contains("Slot")
                        ? ((CompoundTag) items.get(i)).getByte("Slot")
                        : (byte) -1));
            }
        }
        return slots;
    }

    /** The same slots sorted, for the cases whose subject is membership rather than order. */
    private static List<Integer> sortedSlotsOf(CompoundTag blockEntity) {
        List<Integer> slots = new ArrayList<>(slotsOf(blockEntity));
        slots.sort(null);
        return slots;
    }

    /**
     * A crafter block entity. Both state keys are always present, because vanilla's saveAdditional writes them
     * unconditionally and this mod's chunk capture serializes the client's own block entity: a fixture that omits them
     * is a shape vanilla never produces, and a carry-forward tested against it proves nothing.
     */
    private static CompoundTag crafter(int x, int y, int z, int[] disabledSlots, int triggered) {
        CompoundTag blockEntity = blockEntity("minecraft:crafter", x, y, z);
        blockEntity.putIntArray("disabled_slots", disabledSlots);
        blockEntity.putInt("triggered", triggered);
        return blockEntity;
    }

    /** A crafter as the client re-captures one it never opened: the keys present, at their client defaults. */
    private static CompoundTag freshCrafter(int x, int y, int z) {
        return crafter(x, y, z, new int[0], 0);
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
    void theChiseledBookshelfLiteralMatchesTheVanillaRegistryKey() {
        // The per-slot union keys off this exact id string; a drift would silently restore the all-or-nothing
        // rule for the one container the rule is wrong for, and every other test here would still pass.
        TestRegistries.frozen();
        assertEquals(ChunkMerge.CHISELED_BOOKSHELF_ID,
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BlockEntityType.CHISELED_BOOKSHELF).toString(),
                "the production literal itself, not a copy of it in this test");
    }

    @Test
    void aBookshelfInsertUnionsWithTheBooksAlreadyOnDisk() {
        // The bookshelf's capture unit is one slot, so the fresh side names only the book inserted since the last
        // flush. Replacing wholesale would delete the books an earlier flush of this same download already saved.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the shelf carried forward");
        assertEquals(List.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books already on disk survive beside the fourth");
    }

    @Test
    void aBookshelfSlotCapturedTwiceKeepsTheFresherBook() {
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1));
        CompoundTag freshShelf = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, 4, 64, 9);
        freshShelf.put("Items", ItemFixtures.itemsAtSlots(new int[] { 1 }, "minecraft:enchanted_book"));
        CompoundTag fresh = chunkTagWith(freshShelf);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "slot 0 carried forward");
        assertEquals(List.of(0, 1), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "both slots present, and exactly once each: a duplicated slot would let the stale disk book "
                        + "overwrite the fresher one, since vanilla's load is a last-write-wins set");
        ListTag merged = (ListTag) findByPos(fresh, 4, 64, 9).get("Items");
        assertTrue(
                merged.stream().map(t -> (CompoundTag) t)
                        .anyMatch(entry -> (entry.contains("Slot") ? entry.getByte("Slot") : (byte) -1) == 1
                                && "minecraft:enchanted_book".equals(entry.getString("id"))),
                "the slot captured this session keeps the fresher book");
    }

    @Test
    void anEmptyFreshBookshelfStillCarriesTheWholeDiskList() {
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9)); // re-walked, no insert this pass

        assertEquals(1, ChunkMerge.merge(onDisk, fresh));
        assertEquals(List.of(0, 1, 2), sortedSlotsOf(findByPos(fresh, 4, 64, 9)), "a plain re-walk loses nothing");
    }

    @Test
    void anOnDiskBookshelfHoldingNoBooksCarriesNothingAndCountsNothing() {
        // The union's own empty case. A shelf whose prior visit saved no book has nothing to contribute, so the
        // carry must report that it carried nothing: counting it would tell the caller a block entity was
        // recovered when the fresh insert is all there is.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty on-disk shelf carries nothing");
        assertEquals(List.of(3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)), "and the fresh insert stands alone");
    }

    @Test
    void aBookshelfOpenedThisWriteStillUnionsTheBooksAlreadyOnDisk() {
        // A bookshelf reaches the same position set as an opened chest, since a confirmed insert routes to the
        // container bundle the set is built from. Its fresh list is the one slot the player clicked, not the whole
        // shelf, so treating the position as ground truth for every slot would drop every earlier-saved book.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));
        LongSet opened = ChunkMerge.capturedPositions(List.of(new BlockPos(4, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSet.of()),
                "the shelf carries forward even though this write captured a slot of it");
        assertEquals(List.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books an earlier flush saved survive beside the fourth");
    }

    @Test
    void aFreshBlockEntityWithNoCompletePositionCarriesNothing() {
        // A position is what the merge matches on, reads occupancy under, and tests for a fresh open, so a block
        // entity that names none is skipped whole rather than merged against a position it does not have.
        CompoundTag onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        CompoundTag positionless = chest(10, 70, 20);
        positionless.remove("y");
        CompoundTag fresh = BlockEntityFixtures.malformedChunkTagWith(positionless);

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "no position, no carry-forward");
        assertEquals(0, itemCount(((ListTag) fresh.get("block_entities")).getCompound(0)),
                "and the on-disk contents are not written onto it");
    }

    @Test
    void aPartlyEmptiedChestDoesNotResurrectTheItemsTakenOutOfIt() {
        // The union is bookshelf-only on purpose: a chest's fresh list comes from an opened menu, which is ground
        // truth for the whole container, so unioning it would put back what the player watched leave.
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
        LongSet opened = ChunkMerge.capturedPositions(List.of(new BlockPos(10, 70, 20)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSet.of()),
                "an opened menu is ground truth for every slot, so nothing carries back over it");
        CompoundTag merged = findByPos(fresh, 10, 70, 20);
        assertTrue(merged.get("Items") instanceof ListTag items && items.isEmpty(),
                "the chest is written with the present-but-empty list vanilla's own chest writer emits, so the "
                        + "items the player watched leave stay gone");
    }

    @Test
    void anOpenAtOnePositionSaysNothingAboutItsNeighbor() {
        // The set is spent per position, and a gate that spent it as a whole-chunk flag would drop the
        // neighbor's archive. With contents in the exemption that cost is an un-opened container's items.
        CompoundTag onDisk = chunkTagWith(
                chest(1, 64, 1, "minecraft:diamond"),
                chest(2, 64, 2, "minecraft:emerald"));
        CompoundTag fresh = chunkTagWith(chest(1, 64, 1), chest(2, 64, 2));
        LongSet opened = ChunkMerge.capturedPositions(List.of(new BlockPos(1, 64, 1)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSet.of()),
                "one carry-forward, and it is the neighbor's rather than the opened chest's");
        assertEquals(0, itemCount(findByPos(fresh, 1, 64, 1)), "the chest this write saw empty stays empty");
        assertEquals(1, itemCount(findByPos(fresh, 2, 64, 2)),
                "and the chest it never opened keeps what an earlier write archived");
    }

    @Test
    void aCrafterKeepsItsOpenTimeStateAcrossAnyRewrite() {
        // The open-time merge writes these beside "Items"; a re-walk that never re-opens the crafter re-captures
        // it bare, so without the carry-forward the re-write drops the state the earlier open captured.
        CompoundTag onDisk = chunkTagWith(crafter(6, 64, 6, new int[] { 2, 5 }, 1));
        CompoundTag fresh = chunkTagWith(freshCrafter(6, 64, 6));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the crafter carried forward");
        CompoundTag merged = findByPos(fresh, 6, 64, 6);
        assertArrayEquals(new int[] { 2, 5 }, merged.getIntArray("disabled_slots"),
                "the disabled slots survive the re-write");
        assertEquals(1, (merged.contains("triggered") ? merged.getInt("triggered") : -1), "and the powered flag");
    }

    @Test
    void aReOpenedCrafterKeepsItsFresherState() {
        // A crafter the player re-opened is captured from its menu, so its state is a real capture even where
        // every value happens to read as the client default. Naming the position is what says so; without it
        // the merge cannot tell a captured zero from a never-captured one and would carry the stale value.
        CompoundTag onDisk = chunkTagWith(crafter(6, 64, 6, new int[] { 2, 5 }, 1));
        CompoundTag fresh = chunkTagWith(crafter(6, 64, 6, new int[] { 7 }, 0));
        LongSet reopened = ChunkMerge.capturedPositions(List.of(new BlockPos(6, 64, 6)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), reopened, LongSet.of()),
                "the fresh open is authoritative");
        CompoundTag merged = findByPos(fresh, 6, 64, 6);
        assertArrayEquals(new int[] { 7 }, merged.getIntArray("disabled_slots"));
        assertEquals(0, (merged.contains("triggered") ? merged.getInt("triggered") : -1),
                "a re-open that saw the crafter unpowered keeps that, rather than inheriting the stale flag");
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
        // Carrying a slot the saved block-state denies puts an item in the archive that the loaded world shows
        // nowhere, cannot be taken out by hand, and destroys without a drop on the next insert there.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(4, 64, 9).asLong(), 0b1110); // slot 0 emptied since; 1, 2 and 3 occupied

        int mergeBacks = ChunkMerge.merge(onDisk, fresh, occupancy, LongSet.of(), LongSet.of());

        assertEquals(1, mergeBacks, "the shelf still carries the slots that are still occupied");
        assertEquals(List.of(1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "slot 0 is dropped because the block-state says it is empty");
    }

    @Test
    void aBookshelfWithNoRecordedOccupancyKeepsEveryOnDiskSlot() {
        // The over-capture direction when the occupancy is unknown, which is what the no-argument merge means.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSet.of(), LongSet.of()));
        assertEquals(List.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)));
    }

    @Test
    void aBookshelfNotNamedByTheOccupancyMapKeepsEveryOnDiskSlot() {
        // The unknown-occupancy fallback has to survive a map that names OTHER positions, which is the shape
        // production builds: one entry per bookshelf in the chunk, nothing for a shelf whose state was unreadable.
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(11, 70, 2).asLong(), 0b1); // some other shelf entirely

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, occupancy, LongSet.of(), LongSet.of()));
        assertEquals(List.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "an unrecorded position falls back to keeping every slot, not to keeping none");
    }

    @Test
    void aFreshStateCaptureIsNotOverwrittenEvenWhenItsPositionWasNotNamed() {
        // The defensive half of the scalar carry: a fresh side already holding a non-default value is a real
        // capture whatever the position set says, and must not be replaced by the older on-disk one. Both state
        // keys are non-default here, so nothing at all is left for the carry to do and the count says so; with
        // one of them at its default that key carries, and the count could not tell the two halves apart.
        CompoundTag onDisk = chunkTagWith(crafter(6, 64, 6, new int[] { 2, 5 }, 1));
        CompoundTag fresh = chunkTagWith(crafter(6, 64, 6, new int[] { 8 }, 1));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSet.of(), LongSet.of()),
                "a block entity whose every field is its own capture is not a carry-forward");

        CompoundTag merged = findByPos(fresh, 6, 64, 6);
        assertArrayEquals(new int[] { 8 }, merged.getIntArray("disabled_slots"),
                "the fresh non-default capture stands");
        assertEquals(1, (merged.contains("triggered") ? merged.getInt("triggered") : -1),
                "and so does the flag it captured beside it");
    }

    @Test
    void theOccupancyProducerAndTheMergeThatConsumesItAgreeOnTheirKeying() {
        // The producer keys on a packed BlockPos and the merge re-packs one out of the saved tag. A silent
        // disagreement between the two would send every bookshelf to the unknown-occupancy fallback, which
        // looks exactly like the fix working and would restore the phantom this axis exists to prevent.
        RegistryAccess registries = TestRegistries.frozen();
        BlockPos pos = new BlockPos(4, 64, 9);
        BlockState shelf = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(1), true)
                .setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(2), true);
        ChunkSnapshotSource snapshot = SyntheticChunks.withBlockEntityAt(registries, pos, shelf,
                blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, pos.getX(), pos.getY(), pos.getZ()));

        Long2IntOpenHashMap occupancy = ChunkFlushPlan.bookshelfOccupancy(snapshot);

        assertEquals(0b110, occupancy.get(pos.asLong()), "the producer records the mask the block-state carries");
        CompoundTag onDisk = chunkTagWith(bookshelf(4, 64, 9, 0, 1, 2));
        CompoundTag fresh = chunkTagWith(bookshelf(4, 64, 9));
        ChunkMerge.merge(onDisk, fresh, occupancy, LongSet.of(), LongSet.of());
        assertEquals(List.of(1, 2), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "and the merge finds it under the same key, so slot 0 is dropped rather than kept by fallback");
    }

    @Test
    void hasCapturedContentRejectsOpenTimeStateAlone() {
        // A crafter carries state on every visit once opened; counting it as content would tell the resume
        // outline the container was recovered when nothing of its contents was.
        assertFalse(ChunkMerge.hasCapturedContent(crafter(0, 0, 0, new int[] { 1 }, 1)),
                "state without items is not captured content");
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
