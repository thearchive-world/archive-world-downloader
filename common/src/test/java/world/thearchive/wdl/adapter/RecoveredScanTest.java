// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
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
 * with {@code "bees"}), so the outline marks them recovered rather than unsaved. An empty (re-walked, never re-opened)
 * container is not coverage, and a position is coverage only in the dimension it was carried forward in.
 */
class RecoveredScanTest {
    private static final UUID CART_A = new UUID(0x1111_1111_1111_1111L, 0x2222_2222_2222_2222L);
    private static final UUID CART_B = new UUID(0x3333_3333_3333_3333L, 0x4444_4444_4444_4444L);

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void startsEmpty() {
        assertSame(RecoveredCoverage.EMPTY, new RecoveredScan().coverage(Level.OVERWORLD));
    }

    @Test
    void collectsPriorCapturedPositionsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(
                filledChest(10, 70, 20),
                emptyChest(11, 70, 20),
                lecternWithBook(12, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(10, 70, 20)), "a prior-captured chest is coverage");
        assertTrue(coverage.contains(BlockPos.asLong(12, 70, 20)), "a prior-captured lectern is coverage");
        assertFalse(coverage.contains(BlockPos.asLong(11, 70, 20)), "an empty re-walked chest is not coverage");
    }

    @Test
    void collectsPriorCapturedJukeboxAndBeehivePositions() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(
                jukeboxWithDisc(20, 70, 20),
                beehiveWithBees(21, 70, 20),
                emptyJukebox(22, 70, 20),
                emptyBeehive(23, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(20, 70, 20)), "a prior-captured jukebox disc is coverage");
        assertTrue(coverage.contains(BlockPos.asLong(21, 70, 20)), "prior-captured beehive bees are coverage");
        assertFalse(coverage.contains(BlockPos.asLong(22, 70, 20)), "an empty re-walked jukebox is not coverage");
        assertFalse(coverage.contains(BlockPos.asLong(23, 70, 20)), "an empty re-walked beehive is not coverage");
    }

    @Test
    void accumulatesAcrossChunks() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        scan.record(Level.OVERWORLD, chunkWith(filledChest(99, 64, -40)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(10, 70, 20)), "the first chunk's coverage persists");
        assertTrue(coverage.contains(BlockPos.asLong(99, 64, -40)), "the second chunk's coverage is added");
    }

    @Test
    void coverageIsScopedPerDimension() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));

        assertTrue(scan.coverage(Level.OVERWORLD).contains(BlockPos.asLong(10, 70, 20)),
                "the overworld position is overworld coverage");
        assertFalse(scan.coverage(Level.NETHER).contains(BlockPos.asLong(10, 70, 20)),
                "the same coordinate in the nether is not coverage");
    }

    @Test
    void aChunkWithNoCoverageDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterFirst = scan.coverage(Level.OVERWORLD);
        scan.record(Level.OVERWORLD, chunkWith(emptyChest(11, 70, 20)));
        assertSame(afterFirst, scan.coverage(Level.OVERWORLD), "no new coverage means the same published snapshot");
    }

    @Test
    void collectsBookshelfSavedSlotMaskAndKeepsItOutOfTheBooleanSet() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(bookshelf(30, 64, 30, 0, 2)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertEquals(0b101, coverage.bookshelfSavedSlots(BlockPos.asLong(30, 64, 30)),
                "the saved bookshelf slots are a per-slot mask");
        assertFalse(coverage.contains(BlockPos.asLong(30, 64, 30)),
                "a bookshelf never enters the boolean recovered set");
    }

    @Test
    void routesChestAndBookshelfIntoSeparateChannels() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20), bookshelf(11, 70, 20, 1)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(10, 70, 20)), "the chest is boolean coverage");
        assertEquals(0, coverage.bookshelfSavedSlots(BlockPos.asLong(10, 70, 20)), "the chest has no bookshelf mask");
        assertEquals(0b10, coverage.bookshelfSavedSlots(BlockPos.asLong(11, 70, 20)), "the bookshelf has its mask");
        assertFalse(coverage.contains(BlockPos.asLong(11, 70, 20)), "the bookshelf is not boolean coverage");
    }

    @Test
    void emptyBookshelfIsNotCoverage() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(bookshelf(12, 64, 12)));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(Level.OVERWORLD),
                "an empty re-walked bookshelf records no coverage");
    }

    @Test
    void dropsAnItemsEntryWithNoSlotOrAnOutOfRangeSlot() {
        RecoveredScan scan = new RecoveredScan();
        CompoundTag bookshelf = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, 13, 64, 13);
        ListTag items = new ListTag();
        items.add(ItemFixtures.malformedEntryWithoutSlot("minecraft:written_book"));
        items.add(ItemFixtures.entryAtSlot(6, "minecraft:written_book")); // the first out-of-range slot
        items.add(ItemFixtures.entryAtSlot(7, "minecraft:written_book"));
        bookshelf.put("Items", items);
        scan.record(Level.OVERWORLD, BlockEntityFixtures.malformedChunkTagWith(bookshelf));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(Level.OVERWORLD),
                "a missing Slot must not phantom-mark slot 0 and an out-of-range Slot is dropped");
    }

    @Test
    void dropsBlockEntitiesWithoutIntCoordinates() {
        RecoveredScan scan = new RecoveredScan();
        CompoundTag chest = filledChest(14, 64, 14);
        chest.remove("x");
        scan.record(Level.OVERWORLD, BlockEntityFixtures.malformedChunkTagWith(chest));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(Level.OVERWORLD),
                "a block entity without int coordinates cannot be keyed and is dropped");
    }

    @Test
    void aBookshelfMaskGrowsThenRepublishesOnlyWhenItChanges() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(bookshelf(40, 64, 40, 0)));
        RecoveredCoverage afterFirst = scan.coverage(Level.OVERWORLD);
        assertEquals(0b1, afterFirst.bookshelfSavedSlots(BlockPos.asLong(40, 64, 40)), "the first mask is published");

        scan.record(Level.OVERWORLD, chunkWith(bookshelf(40, 64, 40, 0)));
        assertSame(afterFirst, scan.coverage(Level.OVERWORLD), "re-recording the same mask does not republish");
    }

    @Test
    void enderRecoveredIsFalseUntilMarked() {
        assertFalse(new RecoveredScan().coverage(Level.OVERWORLD).enderRecovered(),
                "an unmarked scan reports no restored ender inventory");
    }

    @Test
    void markEnderRecoveredShowsInAnUnrecordedDimensionDefault() {
        RecoveredScan scan = new RecoveredScan();
        scan.markEnderRecovered();
        assertTrue(scan.coverage(Level.NETHER).enderRecovered(),
                "the global ender fact is present even where no chunk recovered");
    }

    @Test
    void markEnderRecoveredIsFoldedIntoPublishedSnapshot() {
        RecoveredScan scan = new RecoveredScan();
        scan.markEnderRecovered();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(10, 70, 20)), "the per-position coverage still publishes");
        assertTrue(coverage.enderRecovered(), "the global ender fact rides on the published snapshot too");
    }

    @Test
    void collectsPriorCapturedEntityUuidsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(Level.OVERWORLD, entityChunkWith(filledVehicle(CART_A), emptyVehicle(CART_B)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.containsEntity(CART_A), "a prior-captured container entity is coverage");
        assertFalse(coverage.containsEntity(CART_B), "an empty re-walked container entity is not coverage");
    }

    @Test
    void recordsTheNestedPassengerContainerAsCoverage() {
        // A chested mule pushed into a minecart saves nested under the minecart's Passengers, so the resume
        // outline must mark it recovered from the nested node or it re-outlines and re-captures it empty.
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(Level.OVERWORLD,
                entityChunkWith(EntityFixtures.entityCarrying(emptyVehicle(CART_A), filledVehicle(CART_B))));

        assertTrue(scan.coverage(Level.OVERWORLD).containsEntity(CART_B),
                "the filled mule nested under the minecart is recovered coverage");
    }

    @Test
    void entityCoverageCoexistsWithBlockCoverageInOneSnapshot() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        scan.recordEntities(Level.OVERWORLD, entityChunkWith(filledVehicle(CART_A)));

        RecoveredCoverage coverage = scan.coverage(Level.OVERWORLD);
        assertTrue(coverage.contains(BlockPos.asLong(10, 70, 20)), "the block coverage survives the entity publish");
        assertTrue(coverage.containsEntity(CART_A), "the entity coverage is folded into the same snapshot");
    }

    @Test
    void anEntityChunkWithNoCapturedContainerDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(Level.OVERWORLD, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterBlock = scan.coverage(Level.OVERWORLD);
        scan.recordEntities(Level.OVERWORLD, entityChunkWith(emptyVehicle(CART_B)));
        assertSame(afterBlock, scan.coverage(Level.OVERWORLD),
                "no new entity coverage means the same published snapshot");
    }

    private static CompoundTag filledVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, "minecraft:diamond");
    }

    private static CompoundTag emptyVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid);
    }

    private static CompoundTag entityChunkWith(CompoundTag... entities) {
        return EntityFixtures.entityChunkTagWith(entities);
    }

    private static CompoundTag bookshelf(int x, int y, int z, int... slots) {
        CompoundTag blockEntity = blockEntity(ChunkMerge.CHISELED_BOOKSHELF_ID, x, y, z);
        String[] books = new String[slots.length];
        Arrays.fill(books, "minecraft:written_book");
        blockEntity.put("Items", ItemFixtures.itemsAtSlots(slots, books));
        return blockEntity;
    }

    private static CompoundTag filledChest(int x, int y, int z) {
        CompoundTag blockEntity = blockEntity("minecraft:chest", x, y, z);
        blockEntity.put("Items", ItemFixtures.items("minecraft:diamond"));
        return blockEntity;
    }

    private static CompoundTag emptyChest(int x, int y, int z) {
        return blockEntity("minecraft:chest", x, y, z);
    }

    private static CompoundTag lecternWithBook(int x, int y, int z) {
        CompoundTag blockEntity = blockEntity("minecraft:lectern", x, y, z);
        blockEntity.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(1)));
        blockEntity.putInt("Page", 0);
        return blockEntity;
    }

    private static CompoundTag jukeboxWithDisc(int x, int y, int z) {
        CompoundTag blockEntity = emptyJukebox(x, y, z);
        blockEntity.put("RecordItem", ItemFixtures.itemTag("minecraft:music_disc_cat"));
        return blockEntity;
    }

    private static CompoundTag emptyJukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    private static CompoundTag beehiveWithBees(int x, int y, int z) {
        CompoundTag blockEntity = emptyBeehive(x, y, z);
        blockEntity.put("bees", BlockEntityFixtures.bees(120));
        return blockEntity;
    }

    private static CompoundTag emptyBeehive(int x, int y, int z) {
        return blockEntity("minecraft:beehive", x, y, z);
    }

    private static CompoundTag chunkWith(CompoundTag... blockEntities) {
        return BlockEntityFixtures.chunkTagWith(blockEntities);
    }
}
