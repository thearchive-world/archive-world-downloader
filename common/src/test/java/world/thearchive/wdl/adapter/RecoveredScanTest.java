// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.BlockEntityFixtures.blockEntity;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.dimension.DimensionType;
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
        assertSame(RecoveredCoverage.EMPTY, new RecoveredScan().coverage(DimensionType.field_18954));
    }

    @Test
    void collectsPriorCapturedPositionsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        // lecternWithBook is a synthetic-field carrier (see its own doc), so this chunk is built unchecked.
        scan.record(DimensionType.field_18954, BlockEntityFixtures.malformedChunkTagWith(
                filledChest(10, 70, 20),
                emptyChest(11, 70, 20),
                lecternWithBook(12, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).asLong()), "a prior-captured chest is coverage");
        assertTrue(coverage.contains(new BlockPos(12, 70, 20).asLong()), "a prior-captured lectern is coverage");
        assertFalse(coverage.contains(new BlockPos(11, 70, 20).asLong()), "an empty re-walked chest is not coverage");
    }

    @Test
    void collectsPriorCapturedJukeboxAndBeehivePositions() {
        RecoveredScan scan = new RecoveredScan();
        // beehiveWithBees is not a real producer's shape (see its own doc), so this chunk is built unchecked.
        scan.record(DimensionType.field_18954, BlockEntityFixtures.malformedChunkTagWith(
                jukeboxWithDisc(20, 70, 20),
                beehiveWithBees(21, 70, 20),
                emptyJukebox(22, 70, 20),
                emptyBeehive(23, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.contains(new BlockPos(20, 70, 20).asLong()), "a prior-captured jukebox disc is coverage");
        assertTrue(coverage.contains(new BlockPos(21, 70, 20).asLong()), "prior-captured beehive bees are coverage");
        assertFalse(coverage.contains(new BlockPos(22, 70, 20).asLong()), "an empty re-walked jukebox is not coverage");
        assertFalse(coverage.contains(new BlockPos(23, 70, 20).asLong()), "an empty re-walked beehive is not coverage");
    }

    @Test
    void accumulatesAcrossChunks() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));
        scan.record(DimensionType.field_18954, chunkWith(filledChest(99, 64, -40)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).asLong()), "the first chunk's coverage persists");
        assertTrue(coverage.contains(new BlockPos(99, 64, -40).asLong()), "the second chunk's coverage is added");
    }

    @Test
    void coverageIsScopedPerDimension() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));

        assertTrue(scan.coverage(DimensionType.field_18954).contains(new BlockPos(10, 70, 20).asLong()),
                "the overworld position is overworld coverage");
        assertFalse(scan.coverage(DimensionType.NETHER).contains(new BlockPos(10, 70, 20).asLong()),
                "the same coordinate in the nether is not coverage");
    }

    @Test
    void aChunkWithNoCoverageDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterFirst = scan.coverage(DimensionType.field_18954);
        scan.record(DimensionType.field_18954, chunkWith(emptyChest(11, 70, 20)));
        assertSame(afterFirst, scan.coverage(DimensionType.field_18954),
                "no new coverage means the same published snapshot");
    }

    @Test
    void dropsBlockEntitiesWithoutIntCoordinates() {
        RecoveredScan scan = new RecoveredScan();
        CompoundTag chest = filledChest(14, 64, 14);
        chest.remove("x");
        scan.record(DimensionType.field_18954, BlockEntityFixtures.malformedChunkTagWith(chest));
        assertSame(RecoveredCoverage.EMPTY, scan.coverage(DimensionType.field_18954),
                "a block entity without int coordinates cannot be keyed and is dropped");
    }

    @Test
    void enderRecoveredIsFalseUntilMarked() {
        assertFalse(new RecoveredScan().coverage(DimensionType.field_18954).enderRecovered(),
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
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).asLong()), "the per-position coverage still publishes");
        assertTrue(coverage.enderRecovered(), "the global ender fact rides on the published snapshot too");
    }

    @Test
    void collectsPriorCapturedEntityUuidsAndExcludesEmptyOnes() {
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(DimensionType.field_18954, entityChunkWith(filledVehicle(CART_A), emptyVehicle(CART_B)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.containsEntity(CART_A), "a prior-captured container entity is coverage");
        assertFalse(coverage.containsEntity(CART_B), "an empty re-walked container entity is not coverage");
    }

    @Test
    void recordsTheNestedPassengerContainerAsCoverage() {
        // A chested mule pushed into a minecart saves nested under the minecart's Passengers, so the resume
        // outline must mark it recovered from the nested node or it re-outlines and re-captures it empty.
        RecoveredScan scan = new RecoveredScan();
        scan.recordEntities(DimensionType.field_18954,
                entityChunkWith(EntityFixtures.entityCarrying(emptyVehicle(CART_A), filledVehicle(CART_B))));

        assertTrue(scan.coverage(DimensionType.field_18954).containsEntity(CART_B),
                "the filled mule nested under the minecart is recovered coverage");
    }

    @Test
    void entityCoverageCoexistsWithBlockCoverageInOneSnapshot() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));
        scan.recordEntities(DimensionType.field_18954, entityChunkWith(filledVehicle(CART_A)));

        RecoveredCoverage coverage = scan.coverage(DimensionType.field_18954);
        assertTrue(coverage.contains(new BlockPos(10, 70, 20).asLong()),
                "the block coverage survives the entity publish");
        assertTrue(coverage.containsEntity(CART_A), "the entity coverage is folded into the same snapshot");
    }

    @Test
    void anEntityChunkWithNoCapturedContainerDoesNotRepublish() {
        RecoveredScan scan = new RecoveredScan();
        scan.record(DimensionType.field_18954, chunkWith(filledChest(10, 70, 20)));
        RecoveredCoverage afterBlock = scan.coverage(DimensionType.field_18954);
        scan.recordEntities(DimensionType.field_18954, entityChunkWith(emptyVehicle(CART_B)));
        assertSame(afterBlock, scan.coverage(DimensionType.field_18954),
                "no new entity coverage means the same published snapshot");
    }

    private static CompoundTag filledVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid, "minecraft:diamond");
    }

    private static CompoundTag emptyVehicle(UUID uuid) {
        return EntityFixtures.containerVehicle("minecraft:chest_minecart", uuid);
    }

    private static CompoundTag entityChunkWith(CompoundTag... entities) {
        // The scan reads the in-chunk Level.Entities at this band, so wrap the carrier in a Level compound.
        CompoundTag chunk = new CompoundTag();
        chunk.put("Level", EntityFixtures.entityChunkTagWith(entities));
        return chunk;
    }

    private static CompoundTag filledChest(int x, int y, int z) {
        CompoundTag blockEntity = blockEntity("minecraft:chest", x, y, z);
        blockEntity.put("Items", ItemFixtures.items("minecraft:diamond"));
        return blockEntity;
    }

    private static CompoundTag emptyChest(int x, int y, int z) {
        return blockEntity("minecraft:chest", x, y, z);
    }

    // No vanilla lectern exists at this band, but RecoveredScan still reads the field-based "Book" (CapturedBlockField
    // .BOOK) for foreign or modded block entities, so a fieldless ender chest stands in as the carrier. A synthetic
    // Book is no producer's output, so a chunk built from this must go through malformedChunkTagWith.
    private static CompoundTag lecternWithBook(int x, int y, int z) {
        CompoundTag blockEntity = blockEntity("minecraft:ender_chest", x, y, z);
        blockEntity.put("Book", ItemFixtures.itemTag(ItemFixtures.writtenBook(1)));
        blockEntity.putInt("Page", 0);
        return blockEntity;
    }

    private static CompoundTag jukeboxWithDisc(int x, int y, int z) {
        // Vanilla JukeboxBlockEntity.saveAdditional always writes IsPlaying/RecordStartTick/TickCount alongside
        // a present RecordItem.
        CompoundTag blockEntity = emptyJukebox(x, y, z);
        blockEntity.put("RecordItem", ItemFixtures.itemTag("minecraft:music_disc_cat"));
        blockEntity.putBoolean("IsPlaying", true);
        blockEntity.putLong("RecordStartTick", 0L);
        blockEntity.putLong("TickCount", 0L);
        return blockEntity;
    }

    private static CompoundTag emptyJukebox(int x, int y, int z) {
        return blockEntity("minecraft:jukebox", x, y, z);
    }

    /**
     * A beehive carrying occupants under {@code "Bees"}, the key {@link ChunkMerge}'s {@code CapturedBlockField.BEES}
     * (and, transitively, {@link RecoveredScan}) reads; vanilla's own {@code BeehiveBlockEntity} persists occupants
     * under {@code "Bees"} instead, so this is not a real producer's shape and a chunk built from it must go through
     * {@link BlockEntityFixtures#malformedChunkTagWith}.
     */
    private static CompoundTag beehiveWithBees(int x, int y, int z) {
        CompoundTag blockEntity = emptyBeehive(x, y, z);
        blockEntity.put("Bees", BlockEntityFixtures.bees(120));
        return blockEntity;
    }

    // No vanilla beehive exists at this band, but RecoveredScan still reads the field-based "Bees" occupant list
    // (CapturedBlockField.BEES) for foreign or modded block entities, so a fieldless ender chest stands in as the
    // carrier.
    private static CompoundTag emptyBeehive(int x, int y, int z) {
        return blockEntity("minecraft:ender_chest", x, y, z);
    }

    private static CompoundTag chunkWith(CompoundTag... blockEntities) {
        return BlockEntityFixtures.chunkTagWith(blockEntities);
    }
}
