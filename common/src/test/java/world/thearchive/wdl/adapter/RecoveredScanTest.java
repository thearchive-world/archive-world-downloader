// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.unhostedBlockEntity;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.RecoveredCoverage;
import world.thearchive.wdl.testsupport.BlockEntityFixtures;
import world.thearchive.wdl.testsupport.EntityFixtures;
import world.thearchive.wdl.testsupport.ItemFixtures;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the resume recovered-coverage scan: {@link RecoveredScan} reads each on-disk chunk the writer
 * thread carries forward and collects the positions that were already captured in a prior session (a block container
 * with non-empty {@code "Items"}, a lectern with a {@code "Book"}, a jukebox with a {@code "RecordItem"}, or a beehive
 * with {@code "Bees"}), so the outline marks them recovered rather than unsaved. An empty (re-walked, never re-opened)
 * container is not coverage, and a position is coverage only in the dimension it was carried forward in.
 */
class RecoveredScanTest {
    private static final UUID CART_A = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
    private static final UUID CART_B = new UUID(0x3333_3333_3333_3333L, 0x4444_4444_4444_4444L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void startsEmpty() {
        assertSame(RecoveredCoverage.EMPTY, new RecoveredScan().coverage(DimensionType.OVERWORLD));
    }

    @Test
    void collectsPriorCapturedPositionsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        // lecternWithBook is a synthetic-field carrier (see its own doc), so this chunk is built unchecked.
        scan.record(DimensionType.OVERWORLD, BlockEntityFixtures.malformedChunkTagWith(
                filledChest(10, 70, 20),
                emptyChest(11, 70, 20),
                lecternWithBook(12, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).toLong()), "a prior-captured chest is coverage");
        assertTrue(coverage.contains(new BlockPos(12, 70, 20).toLong()), "a prior-captured lectern is coverage");
        assertFalse(coverage.contains(new BlockPos(11, 70, 20).toLong()), "an empty re-walked chest is not coverage");
    }

    @Test
    void collectsPriorCapturedJukeboxAndBeehivePositions() {
        RecoveredScan scan = new RecoveredScan();
        // beehiveWithBees is not a real producer's shape (see its own doc), so this chunk is built unchecked.
        scan.record(DimensionType.OVERWORLD, BlockEntityFixtures.malformedChunkTagWith(
                jukeboxWithDisc(20, 70, 20),
                beehiveWithBees(21, 70, 20),
                emptyJukebox(22, 70, 20),
                emptyBeehive(23, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(20, 70, 20).toLong()), "a prior-captured jukebox disc is coverage");
        assertTrue(coverage.contains(new BlockPos(21, 70, 20).toLong()), "prior-captured beehive bees are coverage");
        assertFalse(coverage.contains(new BlockPos(22, 70, 20).toLong()), "an empty re-walked jukebox is not coverage");
        assertFalse(coverage.contains(new BlockPos(23, 70, 20).toLong()), "an empty re-walked beehive is not coverage");
    }

    @Test
    void accumulatesAcrossChunks() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(99, 64, -40)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).toLong()), "the first chunk's coverage persists");
        assertTrue(coverage.contains(new BlockPos(99, 64, -40).toLong()), "the second chunk's coverage is added");
    }

    @Test
    void coverageIsScopedPerDimension() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));

        assertTrue(scan.coverage(DimensionType.OVERWORLD).contains(new BlockPos(10, 70, 20).toLong()),
                "the overworld position is overworld coverage");
        assertFalse(scan.coverage(DimensionType.NETHER).contains(new BlockPos(10, 70, 20).toLong()),
                "the same coordinate in the nether is not coverage");
    }

    @Test
    void aChunkWithNoCoverageDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterFirst = scan.coverage(DimensionType.OVERWORLD);
        scan.record(DimensionType.OVERWORLD, chunkWith(emptyChest(11, 70, 20)));
        assertSame(afterFirst, scan.coverage(DimensionType.OVERWORLD),
                "no new coverage means the same published snapshot");
    }

    @Test
    void collectsBookshelfSavedSlotMaskAndKeepsItOutOfTheBooleanSet() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, bookshelfChunk(bookshelf(30, 64, 30, 0, 2)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertEquals(0b101, coverage.bookshelfSavedSlots(new BlockPos(30, 64, 30).toLong()),
                "the saved bookshelf slots are a per-slot mask");
        assertFalse(coverage.contains(new BlockPos(30, 64, 30).toLong()),
                "a bookshelf never enters the boolean recovered set");
    }

    @Test
    void routesChestAndBookshelfIntoSeparateChannels() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD,
                bookshelfChunk(filledChest(10, 70, 20), bookshelf(11, 70, 20, 1)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).toLong()), "the chest is boolean coverage");
        assertEquals(0, coverage.bookshelfSavedSlots(new BlockPos(10, 70, 20).toLong()),
                "the chest has no bookshelf mask");
        assertEquals(0b10, coverage.bookshelfSavedSlots(new BlockPos(11, 70, 20).toLong()),
                "the bookshelf has its mask");
        assertFalse(coverage.contains(new BlockPos(11, 70, 20).toLong()), "the bookshelf is not boolean coverage");
    }

    @Test
    void emptyBookshelfIsNotCoverage() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, bookshelfChunk(bookshelf(12, 64, 12)));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(DimensionType.OVERWORLD),
                "an empty re-walked bookshelf records no coverage");
    }

    @Test
    void dropsAnItemsEntryWithNoSlotOrAnOutOfRangeSlot() {
        RecoveredScan scan = new RecoveredScan();
        NBTTagCompound bookshelf = bookshelf(13, 64, 13);
        NBTTagList items = new NBTTagList();
        items.appendTag(ItemFixtures.malformedEntryWithoutSlot("minecraft:written_book"));
        items.appendTag(ItemFixtures.entryAtSlot(6, "minecraft:written_book")); // the first out-of-range slot
        items.appendTag(ItemFixtures.entryAtSlot(7, "minecraft:written_book"));
        bookshelf.setTag("Items", items);
        scan.record(DimensionType.OVERWORLD, bookshelfChunk(bookshelf));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(DimensionType.OVERWORLD),
                "a missing Slot must not phantom-mark slot 0 and an out-of-range Slot is dropped");
    }

    @Test
    void dropsBlockEntitiesWithoutIntCoordinates() {
        RecoveredScan scan = new RecoveredScan();
        NBTTagCompound chest = filledChest(14, 64, 14);
        chest.removeTag("x");
        scan.record(DimensionType.OVERWORLD, BlockEntityFixtures.malformedChunkTagWith(chest));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(DimensionType.OVERWORLD),
                "a block entity without int coordinates cannot be keyed and is dropped");
    }

    @Test
    void aBookshelfMaskGrowsThenRepublishesOnlyWhenItChanges() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, bookshelfChunk(bookshelf(40, 64, 40, 0)));
        RecoveredCoverage afterFirst = scan.coverage(DimensionType.OVERWORLD);
        assertEquals(0b1, afterFirst.bookshelfSavedSlots(new BlockPos(40, 64, 40).toLong()),
                "the first mask is published");

        scan.record(DimensionType.OVERWORLD, bookshelfChunk(bookshelf(40, 64, 40, 0)));
        assertSame(afterFirst, scan.coverage(DimensionType.OVERWORLD),
                "re-recording the same mask does not republish");
    }

    @Test
    void enderRecoveredIsFalseUntilMarked() {
        assertFalse(new RecoveredScan().coverage(DimensionType.OVERWORLD).enderRecovered(),
                "an unmarked scan reports no restored ender inventory");
    }

    @Test
    void markEnderRecoveredShowsInAnUnrecordedDimensionDefault() {
        RecoveredScan scan = new RecoveredScan();
        scan.markEnderRecovered();
        assertTrue(scan.coverage(DimensionType.NETHER).enderRecovered(),
                "the global ender fact is present even where no chunk recovered");
    }

    @Test
    void markEnderRecoveredIsFoldedIntoPublishedSnapshot() {
        RecoveredScan scan = new RecoveredScan();
        scan.markEnderRecovered();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).toLong()), "the per-position coverage still publishes");
        assertTrue(coverage.enderRecovered(), "the global ender fact rides on the published snapshot too");
    }

    @Test
    void collectsPriorCapturedEntityUuidsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(DimensionType.OVERWORLD, entityChunkWith(filledVehicle(CART_A), emptyVehicle(CART_B)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.containsEntity(CART_A), "a prior-captured container entity is coverage");
        assertFalse(coverage.containsEntity(CART_B), "an empty re-walked container entity is not coverage");
    }

    @Test
    void recordsTheNestedPassengerContainerAsCoverage() {
        // A chested mule pushed into a minecart saves nested under the minecart's Passengers, so the resume
        // outline must mark it recovered from the nested node or it re-outlines and re-captures it empty.
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(DimensionType.OVERWORLD,
                entityChunkWith(EntityFixtures.entityCarrying(emptyVehicle(CART_A), filledVehicle(CART_B))));

        assertTrue(scan.coverage(DimensionType.OVERWORLD).containsEntity(CART_B),
                "the filled mule nested under the minecart is recovered coverage");
    }

    @Test
    void entityCoverageCoexistsWithBlockCoverageInOneSnapshot() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        scan.recordEntities(DimensionType.OVERWORLD, entityChunkWith(filledVehicle(CART_A)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.OVERWORLD);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).toLong()),
                "the block coverage survives the entity publish");
        assertTrue(coverage.containsEntity(CART_A), "the entity coverage is folded into the same snapshot");
    }

    @Test
    void anEntityChunkWithNoCapturedContainerDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterBlock = scan.coverage(DimensionType.OVERWORLD);
        scan.recordEntities(DimensionType.OVERWORLD, entityChunkWith(emptyVehicle(CART_B)));
        assertSame(afterBlock, scan.coverage(DimensionType.OVERWORLD),
                "no new entity coverage means the same published snapshot");
    }

    private static NBTTagCompound filledVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, "minecraft:diamond");
    }

    private static NBTTagCompound emptyVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid);
    }

    private static NBTTagCompound entityChunkWith(NBTTagCompound... entities) {
        // The scan reads the in-chunk Level.Entities at this band, so wrap the carrier in a Level compound.
        NBTTagCompound chunk = new NBTTagCompound();
        chunk.setTag("Level", EntityFixtures.entityChunkTagWith(entities));
        return chunk;
    }

    /**
     * A chiseled bookshelf's saved tag. No block on this band hosts the type, so it has no producer to take a shape
     * from; the per-slot mask under test is band-agnostic tag code that a save written on a later band reaches.
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

    private static NBTTagCompound filledChest(int x, int y, int z) {
        NBTTagCompound blockEntity = blockEntity("minecraft:chest", x, y, z);
        blockEntity.setTag("Items", ItemFixtures.items("minecraft:diamond"));
        return blockEntity;
    }

    private static NBTTagCompound emptyChest(int x, int y, int z) {
        return blockEntity("minecraft:chest", x, y, z);
    }

    // No vanilla lectern exists at this band, but RecoveredScan still reads the field-based "Book" (CapturedBlockField
    // .BOOK) for foreign or modded block entities, so a fieldless ender chest stands in as the carrier. A synthetic
    // Book is no producer's output, so a chunk built from this must go through malformedChunkTagWith.
    private static NBTTagCompound lecternWithBook(int x, int y, int z) {
        NBTTagCompound blockEntity = blockEntity("minecraft:ender_chest", x, y, z);
        blockEntity.setTag("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(1)));
        blockEntity.setInteger("Page", 0);
        return blockEntity;
    }

    private static NBTTagCompound jukeboxWithDisc(int x, int y, int z) {
        // Vanilla JukeboxBlockEntity.saveAdditional always writes IsPlaying/RecordStartTick/TickCount alongside
        // a present RecordItem.
        NBTTagCompound blockEntity = emptyJukebox(x, y, z);
        blockEntity.setTag("RecordItem", ItemFixtures.itemTag("minecraft:record_cat"));
        blockEntity.setBoolean("IsPlaying", true);
        blockEntity.setLong("RecordStartTick", 0L);
        blockEntity.setLong("TickCount", 0L);
        return blockEntity;
    }

    private static NBTTagCompound emptyJukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    /**
     * A beehive carrying occupants under {@code "Bees"}, the key {@link ChunkMerge}'s {@code CapturedBlockField.BEES}
     * (and, transitively, {@link RecoveredScan}) reads; vanilla's own {@code BeehiveBlockEntity} persists occupants
     * under {@code "Bees"} instead, so this is not a real producer's shape and a chunk built from it must go through
     * {@link BlockEntityFixtures#malformedChunkTagWith}.
     */
    private static NBTTagCompound beehiveWithBees(int x, int y, int z) {
        NBTTagCompound blockEntity = emptyBeehive(x, y, z);
        blockEntity.setTag("Bees", BlockEntityFixtures.bees(120));
        return blockEntity;
    }

    // No vanilla beehive exists at this band, but RecoveredScan still reads the field-based "Bees" occupant list
    // (CapturedBlockField.BEES) for foreign or modded block entities, so a fieldless ender chest stands in as the
    // carrier.
    private static NBTTagCompound emptyBeehive(int x, int y, int z) {
        return blockEntity("minecraft:ender_chest", x, y, z);
    }

    private static NBTTagCompound chunkWith(NBTTagCompound... blockEntities) {
        return BlockEntityFixtures.chunkTagWith(blockEntities);
    }
}
