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
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;

/**
 * The headless guard for the chunk read-merge: {@link ChunkMerge} carries forward every interaction-captured datum (a
 * block container's {@code "Items"}, a lectern's {@code "Book"}/{@code "Page"}, a jukebox disc's {@code "RecordItem"},
 * a beehive's {@code "Bees"}) from the on-disk chunk into a freshly re-captured one, preferring non-empty wherever the
 * write captured nothing of its own there, so a re-walk live-updates the terrain without wiping a chest an earlier
 * write archived, while a container this write captured is left exactly as that capture saw it. Pure
 * {@code NBTTagCompound} in/out, band-agnostic over the pre-1.18 {@code Level.TileEntities} layout, matched by
 * {@code x/y/z}.
 */
class ChunkMergeTest {
    private static NBTTagCompound chest(int x, int y, int z, String... itemIds) {
        return container("minecraft:chest", x, y, z, itemIds);
    }

    /**
     * A second container type distinct from chest, so a same-position type change can be shown; furnace here. Not
     * trapped chest: at 1.12.2 the trapped chest hosts the same {@code TileEntityChest} class as a regular chest, so
     * its saved {@code "id"} is the identical {@code minecraft:chest}, not a distinct type a merge could tell apart.
     */
    private static NBTTagCompound furnace(int x, int y, int z, String... itemIds) {
        return container("minecraft:furnace", x, y, z, itemIds);
    }

    private static NBTTagCompound container(String id, int x, int y, int z, String... itemIds) {
        NBTTagCompound blockEntity = blockEntity(id, x, y, z);
        blockEntity.setTag("Items", ItemFixtures.items(itemIds));
        return blockEntity;
    }

    // No vanilla lectern exists at this band, but ChunkMerge still carries the field-based "Book"/"Page"
    // (CapturedBlockField.BOOK) forward for foreign or modded block entities, so a fieldless ender chest stands in as
    // the carrier. A synthetic Book is no producer's output, so a Book-bearing carrier goes through
    // malformedChunkTagWith, not the fidelity-checked chunkTagWith.
    private static NBTTagCompound lectern(int x, int y, int z, boolean withBook, int page) {
        NBTTagCompound blockEntity = blockEntity("minecraft:ender_chest", x, y, z);
        if (withBook) {
            blockEntity.setTag("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(page + 1)));
            blockEntity.setInteger("Page", page);
        }
        return blockEntity;
    }

    private static int itemCount(NBTTagCompound blockEntity) {
        return blockEntity.getTag("Items") instanceof NBTTagList ? ((NBTTagList) blockEntity.getTag("Items")).tagCount()
                : 0;
    }

    private static NBTTagCompound jukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    private static NBTTagCompound jukeboxWithDisc(int x, int y, int z, String discId) {
        // At 1.18.2 JukeboxBlockEntity.saveAdditional writes only RecordItem, and only when a disc is present; the
        // IsPlaying and tick sidecars are 1.19 additions.
        NBTTagCompound blockEntity = jukebox(x, y, z);
        blockEntity.setTag("RecordItem", ItemFixtures.itemTag(discId));
        return blockEntity;
    }

    // No vanilla beehive exists at this band, but ChunkMerge still carries the field-based "Bees" occupant list
    // (CapturedBlockField.BEES) for foreign or modded block entities, so a fieldless ender chest stands in as the
    // carrier.
    private static NBTTagCompound beehive(int x, int y, int z) {
        return blockEntity("minecraft:ender_chest", x, y, z);
    }

    /**
     * A beehive block entity carrying occupants under {@code "Bees"}, the key {@link ChunkMerge}'s own
     * {@code CapturedBlockField.BEES} carries forward (vanilla's own {@code BeehiveBlockEntity} persists occupants
     * under {@code "Bees"} instead, so this shape is not a real producer's output; a chunk tag built from it must go
     * through {@link BlockEntityFixtures#malformedChunkTagWith}, not the fidelity-checked
     * {@link BlockEntityFixtures#chunkTagWith}).
     */
    private static NBTTagCompound beehiveWithBees(int x, int y, int z, int beeCount) {
        NBTTagCompound blockEntity = beehive(x, y, z);
        int[] ticksInHive = new int[beeCount];
        for (int i = 0; i < beeCount; i++) {
            ticksInHive[i] = 10 * (i + 1);
        }
        blockEntity.setTag("Bees", BlockEntityFixtures.bees(ticksInHive));
        return blockEntity;
    }

    private static String discId(NBTTagCompound blockEntity) {
        return blockEntity.getTag("RecordItem") instanceof NBTTagCompound
                ? ((NBTTagCompound) blockEntity.getTag("RecordItem")).getString("id")
                : "";
    }

    private static int beeCount(NBTTagCompound blockEntity) {
        return blockEntity.getTag("Bees") instanceof NBTTagList ? ((NBTTagList) blockEntity.getTag("Bees")).tagCount()
                : 0;
    }

    /**
     * The on-disk carry-forward is the last store a same-position block replacement makes stale, and the one a
     * placement's in-memory drop cannot reach. The match is position plus block-entity type, which a same-type
     * replacement satisfies exactly, so a chest broken and replaced without ever being opened would otherwise inherit
     * the archived contents of the chest that stood there.
     */
    @Test
    void aPositionThatWasReplacedCarriesNothingForward() {
        NBTTagCompound onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        NBTTagCompound fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(3, 64, 7)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, replaced),
                "the block on disk is the one the placement replaced, so none of it is this block's");
        assertEquals(0, itemCount(findByPos(fresh, 3, 64, 7)),
                "and the chest now standing there stays as empty as the client saw it");
    }

    /** The other half: an untouched position still carries forward, so the guard is not a blanket refusal. */
    @Test
    void aPositionNoPlacementTouchedStillCarriesForward() {
        NBTTagCompound onDisk = chunkTagWith(chest(3, 64, 7, "minecraft:diamond"));
        NBTTagCompound fresh = chunkTagWith(chest(3, 64, 7));
        LongSet replaced = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(9, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, replaced),
                "a placement elsewhere in the chunk says nothing about this position");
        assertEquals(1, itemCount(findByPos(fresh, 3, 64, 7)),
                "so a re-walk keeps what an earlier visit archived here");
    }

    @Test
    void anEmptyFreshChestCarriesForwardTheOnDiskContents() {
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20)); // re-walked, never re-opened: empty

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one container carried forward");
        assertEquals(2, itemCount(findByPos(fresh, 10, 70, 20)), "the prior contents survive the re-walk");
    }

    @Test
    void aRecapturedRichChestKeepsTheFresherContents() {
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:dirt"));
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:emerald"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "fresh is already rich, nothing carried back");
        assertEquals(2, itemCount(findByPos(fresh, 10, 70, 20)), "the fresher capture wins");
    }

    @Test
    void freshTerrainIsAuthoritativeForBlockEntitiesRemovedSinceThePriorSession() {
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        NBTTagCompound fresh = chunkTagWith(); // the chest was broken since; fresh terrain has no block entity

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks);
        assertTrue(((NBTTagList) fresh.getCompoundTag("Level").getTag("TileEntities")).isEmpty(),
                "a removed container is not resurrected");
    }

    @Test
    void aLecternBookAndPageCarryForwardWhenFreshHasNone() {
        NBTTagCompound onDisk = BlockEntityFixtures.malformedChunkTagWith(lectern(5, 64, 5, true, 7));
        NBTTagCompound fresh = chunkTagWith(lectern(5, 64, 5, false, 0));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks);
        NBTTagCompound merged = findByPos(fresh, 5, 64, 5);
        assertTrue(merged.getTag("Book") instanceof NBTTagCompound, "the prior book survives");
        assertEquals(7, (merged.hasKey("Page") ? merged.getInteger("Page") : -1), "and its page");
    }

    @Test
    void aJukeboxDiscCarriesForwardWhenFreshHasNone() {
        NBTTagCompound onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:record_cat"));
        NBTTagCompound fresh = chunkTagWith(jukebox(3, 65, 4)); // re-walked, never re-interacted: no disc client-side

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one jukebox disc carried forward");
        NBTTagCompound merged = findByPos(fresh, 3, 65, 4);
        assertEquals("minecraft:record_cat", discId(merged), "the prior disc survives the re-walk");
    }

    @Test
    void aReInsertedJukeboxDiscKeepsTheFresherDisc() {
        NBTTagCompound onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:record_cat"));
        NBTTagCompound fresh = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:record_13"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "a single-slot jukebox has no sibling to clobber; the fresher disc wins");
        assertEquals("minecraft:record_13", discId(findByPos(fresh, 3, 65, 4)), "the re-inserted disc is kept");
    }

    @Test
    void aTypeChangedJukeboxDiscIsNotGhostedIntoTheReplacementChest() {
        NBTTagCompound onDisk = chunkTagWith(jukeboxWithDisc(3, 65, 4, "minecraft:record_cat"));
        NBTTagCompound fresh = chunkTagWith(chest(3, 65, 4)); // a chest now stands where the jukebox was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the disc must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 3, 65, 4).getTag("RecordItem") instanceof NBTTagCompound,
                "the replacement chest has no disc");
    }

    @Test
    void aBeehiveOccupantsCarryForwardWhenFreshHasNone() {
        NBTTagCompound onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 3));
        NBTTagCompound fresh = chunkTagWith(beehive(8, 72, 1)); // re-walked: the client carries no occupants

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "one beehive's occupants carried forward");
        assertEquals(3, beeCount(findByPos(fresh, 8, 72, 1)), "the prior bees survive the re-walk");
    }

    @Test
    void aRePlacedBeehiveKeepsTheFresherOccupants() {
        NBTTagCompound onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 3));
        // a populated hive placed this session
        NBTTagCompound fresh = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(8, 72, 1, 1));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the atomically captured fresh occupants win; no partial-list clobber");
        assertEquals(1, beeCount(findByPos(fresh, 8, 72, 1)), "the re-placed hive's occupants are kept");
    }

    @Test
    void anEmptyDiskBeesListIsNotCarried() {
        // present-but-empty "Bees" is vanilla's emptied-hive state, distinct from the key being absent
        NBTTagCompound onDisk = BlockEntityFixtures.malformedChunkTagWith(beehiveWithBees(2, 64, 2, 0));
        NBTTagCompound fresh = chunkTagWith(beehive(2, 64, 2));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty disk bees list is not carried");
        assertEquals(0, beeCount(findByPos(fresh, 2, 64, 2)), "the re-walked hive stays empty");
    }

    @Test
    void bothEmptyStaysEmptyAndCountsNothing() {
        NBTTagCompound onDisk = chunkTagWith(chest(1, 64, 1));
        NBTTagCompound fresh = chunkTagWith(chest(1, 64, 1));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
        assertFalse(findByPos(fresh, 1, 64, 1).getTag("Items") instanceof NBTTagList
                && !((NBTTagList) findByPos(fresh, 1, 64, 1).getTag("Items")).isEmpty());
    }

    @Test
    void onlyTheMatchingPositionCarriesForward() {
        NBTTagCompound onDisk = chunkTagWith(
                chest(1, 64, 1, "minecraft:diamond"),
                chest(2, 64, 2, "minecraft:gold_ingot"));
        NBTTagCompound fresh = chunkTagWith(chest(1, 64, 1), chest(2, 64, 2, "minecraft:emerald"));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "only the empty re-captured chest carries forward");
        assertEquals(1, itemCount(findByPos(fresh, 1, 64, 1)), "the prior diamond carried back");
        assertEquals(1, itemCount(findByPos(fresh, 2, 64, 2)), "the rich one kept its fresh emerald");
    }

    @Test
    void aTypeChangedContainerIsNewAndEmptyNotGhosted() {
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = chunkTagWith(furnace(10, 70, 20)); // a furnace now stands where the chest was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the chest's items must not ghost into a furnace at the same position");
        assertFalse(findByPos(fresh, 10, 70, 20).getTag("Items") instanceof NBTTagList
                && !((NBTTagList) findByPos(fresh, 10, 70, 20).getTag("Items")).isEmpty(),
                "the replacement furnace stays empty, not the chest's 27 slots");
    }

    @Test
    void aTypeChangedLecternBookIsNotGhostedIntoTheReplacementChest() {
        NBTTagCompound onDisk = BlockEntityFixtures.malformedChunkTagWith(lectern(5, 64, 5, true, 7));
        NBTTagCompound fresh = chunkTagWith(chest(5, 64, 5)); // a chest now stands where the lectern was

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(0, mergeBacks, "the lectern's book must not ghost into a chest at the same position");
        assertFalse(findByPos(fresh, 5, 64, 5).getTag("Book") instanceof NBTTagCompound,
                "the replacement chest has no book");
    }

    @Test
    void aRevisitReflectsPlacedBlockEntitiesAndCarriesTheSurvivingContainerForward() {
        // A revisit re-flush drives the merge from the fresh capture: a furnace placed since the first flush is
        // present (the change lands), and the chest that survived carries its earlier-saved contents forward.
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20), furnace(11, 70, 20));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "only the surviving chest carries forward");
        assertEquals(1, itemCount(findByPos(fresh, 10, 70, 20)), "the chest keeps its earlier-saved diamond");
        assertFalse(findByPos(fresh, 11, 70, 20).getTag("Items") instanceof NBTTagList
                && !((NBTTagList) findByPos(fresh, 11, 70, 20).getTag("Items")).isEmpty(),
                "the newly placed furnace is present and empty (the revisit change is reflected)");
    }

    @Test
    void onDiskLightNeverCarriesForward() {
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond"));
        onDisk.setBoolean("isLightOn", true);
        NBTTagList onDiskSections = new NBTTagList();
        NBTTagCompound onDiskSection = new NBTTagCompound();
        onDiskSection.setByte("Y", (byte) 0);
        onDiskSection.setByteArray("BlockLight", new byte[2048]);
        onDiskSections.appendTag(onDiskSection);
        onDisk.setTag("sections", onDiskSections);

        // The fresh chunk carries its own section at the same Y with no light layers on it. Without a section
        // there the light assertion below iterates nothing and holds for any merge at all.
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20)); // re-walked, gate-false: no light tags
        NBTTagList freshSections = new NBTTagList();
        NBTTagCompound freshSection = new NBTTagCompound();
        freshSection.setByte("Y", (byte) 0);
        freshSections.appendTag(freshSection);
        fresh.setTag("sections", freshSections);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the chest must carry forward, proving merge ran its real path");
        assertFalse(fresh.getBoolean("isLightOn"),
                "on-disk isLightOn must not carry onto a gate-false fresh chunk");
        boolean anyLightCarried = false;
        for (NBTBase sectionTag : fresh.getTagList("sections", 10)) {
            NBTTagCompound section = (NBTTagCompound) sectionTag;
            if (section.hasKey("BlockLight", 7) || section.hasKey("SkyLight", 7)) {
                anyLightCarried = true;
            }
        }
        assertFalse(anyLightCarried, "on-disk light layers must not carry onto fresh sections");
    }

    @Test
    void aChunkWithNoFreshBlockEntitiesDoesNothing() {
        NBTTagCompound onDisk = chunkTagWith(chest(1, 64, 1, "minecraft:diamond"));
        NBTTagCompound fresh = new NBTTagCompound(); // no block_entities key at all

        assertEquals(0, ChunkMerge.merge(onDisk, fresh));
    }

    /**
     * A chiseled bookshelf's saved tag. No block on this band hosts the type, so it has no producer to take a shape
     * from; the per-slot union under test is band-agnostic tag code that a save written on a later band reaches.
     */
    private static NBTTagCompound bookshelf(int x, int y, int z, int... slots) {
        NBTTagCompound blockEntity = unhostedBlockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, x, y, z);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        blockEntity.setTag("Items", ItemFixtures.itemsAtSlots(slots, books));
        return blockEntity;
    }

    private static NBTTagCompound bookshelfChunk(NBTTagCompound... blockEntities) {
        return BlockEntityFixtures.malformedChunkTagWith(blockEntities);
    }

    /**
     * The slots an Items list names, in list order and WITH duplicates. A set would hide the one corruption this axis
     * can produce: two entries for one slot, where vanilla's load does a last-write-wins set and the stale entry
     * therefore beats the fresher one.
     */
    private static List<Integer> slotsOf(NBTTagCompound blockEntity) {
        List<Integer> slots = new ArrayList<>();
        if (blockEntity.getTag("Items") instanceof NBTTagList) {
            NBTTagList items = (NBTTagList) blockEntity.getTag("Items");
            for (int i = 0; i < items.tagCount(); i++) {
                NBTTagCompound entry = (NBTTagCompound) items.get(i);
                slots.add((int) (entry.hasKey("Slot") ? entry.getByte("Slot") : (byte) -1));
            }
        }
        return slots;
    }

    /** The same slots sorted, for the cases whose subject is membership rather than order. */
    private static List<Integer> sortedSlotsOf(NBTTagCompound blockEntity) {
        List<Integer> slots = new ArrayList<>(slotsOf(blockEntity));
        Collections.sort(slots);
        return slots;
    }

    private static NBTTagCompound brewingStand(int x, int y, int z, short brewTime, byte fuel) {
        NBTTagCompound blockEntity = blockEntity("minecraft:brewing_stand", x, y, z);
        blockEntity.setShort("BrewTime", brewTime);
        blockEntity.setByte("Fuel", fuel);
        return blockEntity;
    }

    /** A brewing stand as the client re-captures one it never opened: the keys present, at their defaults. */
    private static NBTTagCompound freshBrewingStand(int x, int y, int z) {
        return brewingStand(x, y, z, (short) 0, (byte) 0);
    }

    @Test
    void aBookshelfInsertUnionsWithTheBooksAlreadyOnDisk() {
        // The bookshelf's capture unit is one slot, so the fresh side names only the book inserted since the last
        // flush. Replacing wholesale would delete the books an earlier flush of this same download already saved.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "the shelf carried forward");
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books already on disk survive beside the fourth");
    }

    @Test
    void aBookshelfSlotCapturedTwiceKeepsTheFresherBook() {
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1));
        NBTTagCompound freshShelf = bookshelf(4, 64, 9);
        freshShelf.setTag("Items", ItemFixtures.itemsAtSlots(new int[] { 1 }, "minecraft:enchanted_book"));
        NBTTagCompound fresh = bookshelfChunk(freshShelf);

        int mergeBacks = ChunkMerge.merge(onDisk, fresh);

        assertEquals(1, mergeBacks, "slot 0 carried forward");
        assertEquals(ImmutableList.of(0, 1), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "both slots present, and exactly once each: a duplicated slot would let the stale disk book "
                        + "overwrite the fresher one, since vanilla's load is a last-write-wins set");
        NBTTagList merged = (NBTTagList) findByPos(fresh, 4, 64, 9).getTag("Items");
        boolean slot1IsFresherBook = false;
        for (NBTBase entryTag : merged) {
            NBTTagCompound entry = (NBTTagCompound) entryTag;
            if ((entry.hasKey("Slot") ? entry.getByte("Slot") : (byte) -1) == 1
                    && "minecraft:enchanted_book".equals(entry.getString("id"))) {
                slot1IsFresherBook = true;
            }
        }
        assertTrue(slot1IsFresherBook, "the slot captured this session keeps the fresher book");
    }

    @Test
    void anEmptyFreshBookshelfStillCarriesTheWholeDiskList() {
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9)); // re-walked, no insert this pass

        assertEquals(1, ChunkMerge.merge(onDisk, fresh));
        assertEquals(ImmutableList.of(0, 1, 2), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "a plain re-walk loses nothing");
    }

    @Test
    void anOnDiskBookshelfHoldingNoBooksCarriesNothingAndCountsNothing() {
        // The union's own empty case. A shelf whose prior visit saved no book has nothing to contribute, so the
        // carry must report that it carried nothing: counting it would tell the caller a block entity was
        // recovered when the fresh insert is all there is.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "an empty on-disk shelf carries nothing");
        assertEquals(Collections.singletonList(3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "and the fresh insert stands alone");
    }

    @Test
    void aBookshelfOpenedThisWriteStillUnionsTheBooksAlreadyOnDisk() {
        // A bookshelf reaches the same position set as an opened chest, since a confirmed insert routes to the
        // container bundle the set is built from. Its fresh list is the one slot the player clicked, not the whole
        // shelf, so treating the position as ground truth for every slot would drop every earlier-saved book.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(4, 64, 9)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "the shelf carries forward even though this write captured a slot of it");
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "the three books an earlier flush saved survive beside the fourth");
    }

    @Test
    void aFreshBlockEntityWithNoCompletePositionCarriesNothing() {
        // A position is what the merge matches on, reads occupancy under, and tests for a fresh open, so a block
        // entity that names none is skipped whole rather than merged against a position it does not have.
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound positionless = chest(10, 70, 20);
        positionless.removeTag("y");
        NBTTagCompound fresh = BlockEntityFixtures.malformedChunkTagWith(positionless);

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "no position, no carry-forward");
        assertEquals(0,
                itemCount(((NBTTagList) fresh.getCompoundTag("Level").getTag("TileEntities")).getCompoundTagAt(0)),
                "and the on-disk contents are not written onto it");
    }

    @Test
    void aPartlyEmptiedChestDoesNotResurrectTheItemsTakenOutOfIt() {
        // The union is bookshelf-only on purpose: a chest's fresh list comes from an opened menu, which is ground
        // truth for the whole container, so unioning it would put back what the player watched leave.
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20, "minecraft:dirt"));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh), "nothing carried back into a re-opened chest");
        assertEquals(1, itemCount(findByPos(fresh, 10, 70, 20)), "only what the re-open saw is kept");
    }

    @Test
    void aChestOpenedThisWriteAndFoundEmptyIsWrittenEmpty() {
        // The whole difficulty is that both fresh sides are an empty "Items": an opened chest and a re-walked one
        // serialize identically, and only the position set says the emptiness was seen rather than never captured.
        NBTTagCompound onDisk = chunkTagWith(chest(10, 70, 20, "minecraft:diamond", "minecraft:gold_ingot"));
        NBTTagCompound fresh = chunkTagWith(chest(10, 70, 20));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(10, 70, 20)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "an opened menu is ground truth for every slot, so nothing carries back over it");
        NBTTagCompound merged = findByPos(fresh, 10, 70, 20);
        assertTrue(merged.getTag("Items") instanceof NBTTagList && ((NBTTagList) merged.getTag("Items")).isEmpty(),
                "the chest is written with the present-but-empty list vanilla's own chest writer emits, so the "
                        + "items the player watched leave stay gone");
    }

    @Test
    void anOpenAtOnePositionSaysNothingAboutItsNeighbor() {
        // The set is spent per position, and a gate that spent it as a whole-chunk flag would drop the
        // neighbor's archive. With contents in the exemption that cost is an un-opened container's items.
        NBTTagCompound onDisk = chunkTagWith(
                chest(1, 64, 1, "minecraft:diamond"),
                chest(2, 64, 2, "minecraft:emerald"));
        NBTTagCompound fresh = chunkTagWith(chest(1, 64, 1), chest(2, 64, 2));
        LongSet opened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(1, 64, 1)));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), opened, LongSets.EMPTY_SET),
                "one carry-forward, and it is the neighbor's rather than the opened chest's");
        assertEquals(0, itemCount(findByPos(fresh, 1, 64, 1)), "the chest this write saw empty stays empty");
        assertEquals(1, itemCount(findByPos(fresh, 2, 64, 2)),
                "and the chest it never opened keeps what an earlier write archived");
    }

    @Test
    void aReOpenedBrewingStandKeepsItsFresherState() {
        // A brewing stand the player re-opened is captured from its menu, so its state is a real capture even where
        // every value happens to read as the client default. Naming the position is what says so; without it
        // the merge cannot tell a captured zero from a never-captured one and would carry the stale value.
        NBTTagCompound onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        NBTTagCompound fresh = chunkTagWith(brewingStand(6, 64, 6, (short) 60, (byte) 0));
        LongSet reopened = ChunkMerge.capturedPositions(ImmutableList.of(new BlockPos(6, 64, 6)));

        assertEquals(0, ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), reopened, LongSets.EMPTY_SET),
                "the fresh open is authoritative");
        NBTTagCompound merged = findByPos(fresh, 6, 64, 6);
        assertEquals((short) 60, merged.getShort("BrewTime"));
        assertEquals((byte) 0, (merged.hasKey("Fuel") ? merged.getByte("Fuel") : (byte) -1),
                "a re-open that saw the stand out of fuel keeps that, rather than inheriting the stale fuel");
    }

    @Test
    void aBrewingStandKeepsItsBrewTimeAndFuelAcrossAnyRewrite() {
        NBTTagCompound onDisk = chunkTagWith(brewingStand(7, 64, 7, (short) 220, (byte) 12));
        NBTTagCompound fresh = chunkTagWith(freshBrewingStand(7, 64, 7));

        assertEquals(1, ChunkMerge.merge(onDisk, fresh));
        NBTTagCompound merged = findByPos(fresh, 7, 64, 7);
        assertEquals((short) 220, (merged.hasKey("BrewTime") ? merged.getShort("BrewTime") : (short) -1),
                "a mid-brew stand does not restart");
        assertEquals((byte) 12, (merged.hasKey("Fuel") ? merged.getByte("Fuel") : (byte) -1),
                "and keeps its blaze powder");
    }

    @Test
    void aBookTakenOutBetweenVisitsIsNotWrittenBackIntoAnEmptySlot() {
        // Carrying a slot the saved block-state denies puts an item in the archive that the loaded world shows
        // nowhere, cannot be taken out by hand, and destroys without a drop on the next insert there.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(4, 64, 9).toLong(), 0b1110); // slot 0 emptied since; 1, 2 and 3 occupied

        int mergeBacks = ChunkMerge.merge(onDisk, fresh, occupancy, LongSets.EMPTY_SET, LongSets.EMPTY_SET);

        assertEquals(1, mergeBacks, "the shelf still carries the slots that are still occupied");
        assertEquals(ImmutableList.of(1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "slot 0 is dropped because the block-state says it is empty");
    }

    @Test
    void aBookshelfWithNoRecordedOccupancyKeepsEveryOnDiskSlot() {
        // The over-capture direction when the occupancy is unknown, which is what the no-argument merge means.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));

        assertEquals(1,
                ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, LongSets.EMPTY_SET));
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)));
    }

    @Test
    void aBookshelfNotNamedByTheOccupancyMapKeepsEveryOnDiskSlot() {
        // The unknown-occupancy fallback has to survive a map that names OTHER positions, which is the shape
        // production builds: one entry per bookshelf in the chunk, nothing for a shelf whose state was unreadable.
        NBTTagCompound onDisk = bookshelfChunk(bookshelf(4, 64, 9, 0, 1, 2));
        NBTTagCompound fresh = bookshelfChunk(bookshelf(4, 64, 9, 3));
        Long2IntOpenHashMap occupancy = ChunkMerge.occupancyMap();
        occupancy.put(new BlockPos(11, 70, 2).toLong(), 0b1); // some other shelf entirely

        assertEquals(1, ChunkMerge.merge(onDisk, fresh, occupancy, LongSets.EMPTY_SET, LongSets.EMPTY_SET));
        assertEquals(ImmutableList.of(0, 1, 2, 3), sortedSlotsOf(findByPos(fresh, 4, 64, 9)),
                "an unrecorded position falls back to keeping every slot, not to keeping none");
    }

    @Test
    void aFreshStateCaptureIsNotOverwrittenEvenWhenItsPositionWasNotNamed() {
        // The defensive half of the scalar carry: a fresh side already holding a non-default value is a real
        // capture whatever the position set says, and must not be replaced by the older on-disk one. Both state
        // keys are non-default here, so nothing at all is left for the carry to do and the count says so; with
        // one of them at its default that key carries, and the count could not tell the two halves apart.
        NBTTagCompound onDisk = chunkTagWith(brewingStand(6, 64, 6, (short) 220, (byte) 12));
        NBTTagCompound fresh = chunkTagWith(brewingStand(6, 64, 6, (short) 180, (byte) 7));

        assertEquals(0,
                ChunkMerge.merge(onDisk, fresh, ChunkMerge.occupancyMap(), LongSets.EMPTY_SET, LongSets.EMPTY_SET),
                "a block entity whose every field is its own capture is not a carry-forward");

        NBTTagCompound merged = findByPos(fresh, 6, 64, 6);
        assertEquals((short) 180, merged.getShort("BrewTime"),
                "the fresh non-default capture stands");
        assertEquals((byte) 7, (merged.hasKey("Fuel") ? merged.getByte("Fuel") : (byte) -1),
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
        assertTrue(ChunkMerge.hasCapturedContent(jukeboxWithDisc(0, 0, 0, "minecraft:record_cat")),
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
