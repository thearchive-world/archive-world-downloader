// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityPacketAccumulatorTest {
    private static final UUID UUID_A = new UUID(0, 1);
    private static final UUID UUID_B = new UUID(0, 2);
    private static final long CHUNK = 100L;
    private static final long FAR = 200L;
    private static final long ELSEWHERE = 300L;
    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "the_nether";

    private static EntityPacketAccumulator<String, String, String> accumulator() {
        return new EntityPacketAccumulator<>(OVERWORLD);
    }

    @Test
    void aSpawnedEntityDrainsByItsChunk() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1.0, 64.0, 2.0, 0f, 0f), "spawn-packet");

        List<PacketEntity<String, String, String>> drained = accumulator.dropChunk(OVERWORLD, CHUNK);

        assertEquals(1, drained.size());
        assertEquals(UUID_A, drained.get(0).uuid(), "the save-layer UUID rides from the spawn");
        assertEquals("spawn-packet", drained.get(0).spawn(), "the spawn payload is held for reconstruction");
        assertTrue(drained.get(0).synced().isEmpty(), "no SetEntityData seen yet");
        assertEquals(ImmutableSet.of(), accumulator.chunks(OVERWORLD), "drained entries leave the accumulator");
        assertFalse(accumulator.dropChunk(OVERWORLD, CHUNK).size() > 0, "a re-drain of the same chunk yields nothing");
    }

    @Test
    void spawnSeedsThePositionOntoTheDrainRecord() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1.5, 64.0, 2.5, 45f, 10f), "spawn-packet");

        EntityPos pos = accumulator.dropChunk(OVERWORLD, CHUNK).get(0).pos();

        assertEquals(1.5, pos.x());
        assertEquals(64.0, pos.y());
        assertEquals(2.5, pos.z());
        assertEquals(45f, pos.yRot());
        assertEquals(10f, pos.xRot());
    }

    @Test
    void syncedValuesMergeByKeyWithTheLatestWinning() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn-packet");
        accumulator.recordData(7, 8, "item=apple");
        accumulator.recordData(7, 9, "rotation=2");
        accumulator.recordData(7, 8, "item=sword");

        List<String> synced = accumulator.dropChunk(OVERWORLD, CHUNK).get(0).synced();

        assertEquals(2, synced.size(), "one value per accessor id, not one per packet");
        assertTrue(synced.contains("item=sword"), "the latest value for an accessor wins");
        assertTrue(synced.contains("rotation=2"));
        assertFalse(synced.contains("item=apple"), "the superseded value is gone");
    }

    @Test
    void equipmentMergesBySlotWithTheLatestWinning() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");
        accumulator.recordEquipment(7, 0, "head=helmet");
        accumulator.recordEquipment(7, 1, "chest=plate");
        accumulator.recordEquipment(7, 0, "head=crown");

        List<String> equipment = accumulator.dropChunk(OVERWORLD, CHUNK).get(0).equipment();

        assertEquals(2, equipment.size(), "one value per slot, not one per packet");
        assertTrue(equipment.contains("head=crown"), "the latest value for a slot wins");
        assertTrue(equipment.contains("chest=plate"));
        assertFalse(equipment.contains("head=helmet"), "the superseded slot value is gone");
    }

    @Test
    void passengersAreReplacedWholesale() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.recordPassengers(7, new int[] { 10, 11 });
        accumulator.recordPassengers(7, new int[] { 12 });

        assertArrayEquals(new int[] { 12 }, accumulator.dropChunk(OVERWORLD, CHUNK).get(0).passengers());
    }

    @Test
    void theLeashHolderIsRecordedAndDefaultsToNone() {
        EntityPacketAccumulator<String, String, String> unleashed = accumulator();
        unleashed.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");
        assertEquals(0, unleashed.dropChunk(OVERWORLD, CHUNK).get(0).leashHolderId(), "no link seen, no leash");

        EntityPacketAccumulator<String, String, String> leashed = accumulator();
        leashed.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");
        leashed.recordLeash(7, 42);
        assertEquals(42, leashed.dropChunk(OVERWORLD, CHUNK).get(0).leashHolderId());
    }

    @Test
    void aRepositionedEntityReHomesToTheChunkItEndsIn() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1.0, 64.0, 2.0, 0f, 0f), "mob");

        accumulator.reposition(7, FAR, new EntityPos(300.0, 70.0, 305.0, 90f, 5f));

        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(OVERWORLD), "re-homed to the new chunk");
        assertTrue(accumulator.dropChunk(OVERWORLD, CHUNK).isEmpty(), "no longer drains from the old chunk");
        EntityPos pos = accumulator.dropChunk(OVERWORLD, FAR).get(0).pos();
        assertEquals(300.0, pos.x(), "the final position rides the drain record");
        assertEquals(305.0, pos.z());
        assertEquals(90f, pos.yRot());
    }

    @Test
    void positionOfReturnsTheCurrentPositionAndNullForUnknown() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1.0, 64.0, 2.0, 30f, 0f), "mob");

        EntityPos pos = accumulator.positionOf(7);
        assertEquals(1.0, pos.x());
        assertEquals(2.0, pos.z());
        assertEquals(30f, pos.yRot());
        assertNull(accumulator.positionOf(8), "an unspawned id has no position");
    }

    @Test
    void postSpawnPacketsForAnUnknownIdAreIgnored() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.recordData(7, 8, "item=apple");
        accumulator.recordEquipment(7, 0, "head=helmet");
        accumulator.recordPassengers(7, new int[] { 10 });
        accumulator.recordLeash(7, 42);
        accumulator.reposition(7, FAR, new EntityPos(1, 1, 1, 0f, 0f));

        assertEquals(ImmutableSet.of(), accumulator.chunks(OVERWORLD), "no spawn, no entry");
        assertNull(accumulator.positionOf(7));
    }

    @Test
    void tracksReportsWhetherAnIdHasBeenSpawned() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn-packet");

        assertTrue(accumulator.tracks(7), "a spawned id is tracked");
        assertFalse(accumulator.tracks(8), "an unspawned id is not");
    }

    @Test
    void drainingOneChunkLeavesTheOthersIntact() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "in-chunk");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "other-chunk");

        assertEquals(ImmutableSet.of(CHUNK, FAR), accumulator.chunks(OVERWORLD));
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "only the drained chunk");
        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(OVERWORLD), "the other chunk is still held");
    }

    @Test
    void settingPassengersReHomesTheRidersToTheVehiclesChunk() {
        // A rider is positioned client-side at its vehicle, so the server need not move it; left alone its accumulated
        // chunk would lag the vehicle and they would drain in different batches and fail to nest.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "rider"); // a different chunk at spawn

        accumulator.recordPassengers(1, new int[] { 2 });

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD), "the rider joined the vehicle's chunk");
        assertEquals(2, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "vehicle and rider drain in one batch");
    }

    @Test
    void movingTheVehicleCarriesItsRidersToTheNewChunk() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.spawn(2, UUID_B, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "rider");
        accumulator.recordPassengers(1, new int[] { 2 });

        accumulator.reposition(1, FAR, new EntityPos(300, 64, 305, 0f, 0f));

        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(OVERWORLD),
                "the rider followed the vehicle to the new chunk");
        assertEquals(2, accumulator.dropChunk(OVERWORLD, FAR).size(), "vehicle and rider still drain together");
    }

    @Test
    void aPassengerArrivingAfterItsVehiclesSetPassengersIsNotReHomed() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.recordPassengers(1, new int[] { 2 });
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "rider");

        assertEquals(ImmutableSet.of(CHUNK, FAR), accumulator.chunks(OVERWORLD),
                "the late rider is not re-homed to the vehicle");
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "only the vehicle drains from its chunk");
        assertEquals(1, accumulator.dropChunk(OVERWORLD, FAR).size(), "the rider drains standalone from its own chunk");
    }

    @Test
    void aRiderTwoLevelsDeepFollowsTheStackToTheVehiclesChunk() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "minecart");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "mount");
        accumulator.spawn(3, new UUID(0, 3), FAR, new EntityPos(0, 0, 0, 0f, 0f), "rider");
        accumulator.recordPassengers(2, new int[] { 3 }); // the inner seat first, or one level would reach it anyway

        accumulator.recordPassengers(1, new int[] { 2 });

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD),
                "the whole stack joined the vehicle's chunk");
        List<PacketEntity<String, String, String>> drained = accumulator.dropChunk(OVERWORLD, CHUNK);
        assertEquals(3, drained.size(), "so all three drain in one batch and nest");
        // The mount's list is one id long, which is the shape that lets a worklist wrapping the held array rather than
        // copying it overwrite the vehicle's own passengers in place with its rider's.
        assertArrayEquals(new int[] { 2 }, passengersOf(drained, 1), "the walk left the vehicle's own list alone");
        assertArrayEquals(new int[] { 3 }, passengersOf(drained, 2), "and the mount's, which it walked through");
    }

    @Test
    void everyRiderOfOneVehicleFollowsIt() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "boat");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "mount");
        accumulator.spawn(3, new UUID(0, 3), FAR, new EntityPos(0, 0, 0, 0f, 0f), "first rider");
        accumulator.spawn(4, new UUID(0, 4), FAR, new EntityPos(0, 0, 0, 0f, 0f), "second rider");
        accumulator.recordPassengers(2, new int[] { 3, 4 });

        accumulator.recordPassengers(1, new int[] { 2 });

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD), "both riders joined the vehicle's chunk");
        assertEquals(4, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "so all four drain in one batch");
    }

    private static int[] passengersOf(List<PacketEntity<String, String, String>> drained, int id) {
        return drained.stream().filter(entity -> entity.id() == id).findFirst().get().passengers();
    }

    @Test
    void aPassengerCycleTerminates() throws Exception {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.spawn(2, UUID_B, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "rider");
        accumulator.recordPassengers(1, new int[] { 2 });

        // A lost cycle guard spins forever: a same-thread call hangs the gate, a non-daemon thread outlives
        // the suite, and a JUnit Timeout is same-thread by default so it is not read until the method returns.
        Thread walk = new Thread(() -> {
            accumulator.recordPassengers(2, new int[] { 1 }); // the rider names its own vehicle back
            accumulator.reposition(1, FAR, new EntityPos(300, 64, 305, 0f, 0f));
        });
        walk.setDaemon(true);
        walk.start();
        walk.join(10_000);

        assertFalse(walk.isAlive(), "each id is re-homed once and the walk ends");
        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(OVERWORLD),
                "and the cycle re-homes to the vehicle's chunk");
        assertEquals(2, accumulator.dropChunk(OVERWORLD, FAR).size(), "both still drain together");
    }

    @Test
    void aReusedIdReplacesThePriorEntityAndClearsAllItsState() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1, 1, 1, 0f, 0f), "old");
        accumulator.recordData(7, 8, "item=apple");
        accumulator.recordEquipment(7, 0, "head=helmet");
        accumulator.recordPassengers(7, new int[] { 10 });
        accumulator.recordLeash(7, 42);
        accumulator.spawn(7, UUID_B, CHUNK, new EntityPos(2, 2, 2, 0f, 0f), "new");

        PacketEntity<String, String, String> drained = accumulator.dropChunk(OVERWORLD, CHUNK).get(0);

        assertEquals(UUID_B, drained.uuid(), "the latest spawn's UUID");
        assertEquals("new", drained.spawn());
        assertTrue(drained.synced().isEmpty(), "the prior synced item did not carry over");
        assertTrue(drained.equipment().isEmpty(), "the prior equipment did not carry over");
        assertEquals(0, drained.passengers().length, "the prior passengers did not carry over");
        assertEquals(0, drained.leashHolderId(), "the prior leash did not carry over");
    }

    @Test
    void aSpawnBeyondTheTrackingCeilingIsSkippedAndCounted() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        for (int id = 0; id < EntityPacketAccumulator.MAX_TRACKED_ENTITIES; id++) {
            accumulator.spawn(id, new UUID(1, id), CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn");
        }
        assertEquals(0, accumulator.droppedAtCapacity(), "filling to the ceiling drops nothing");

        accumulator.spawn(EntityPacketAccumulator.MAX_TRACKED_ENTITIES, UUID_A, FAR,
                new EntityPos(0, 0, 0, 0f, 0f), "one-over");

        assertEquals(1, accumulator.droppedAtCapacity());
        assertEquals(EntityPacketAccumulator.MAX_TRACKED_ENTITIES + 1L, accumulator.spawnCount(),
                "the fed-spawn tally counts the skipped spawn as received");
        assertEquals(0, accumulator.dropChunk(OVERWORLD, FAR).size(), "the skipped entity is never drained");
    }

    @Test
    void aReusedIdStillReplacesAtTheTrackingCeiling() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        for (int id = 0; id < EntityPacketAccumulator.MAX_TRACKED_ENTITIES; id++) {
            accumulator.spawn(id, new UUID(1, id), CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn");
        }

        accumulator.spawn(7, UUID_B, FAR, new EntityPos(2, 2, 2, 0f, 0f), "replacement");

        assertEquals(0, accumulator.droppedAtCapacity(), "a reused id is a replacement, not growth");
        List<PacketEntity<String, String, String>> drained = accumulator.dropChunk(OVERWORLD, FAR);
        assertEquals(1, drained.size(), "the replacement re-homed to its new chunk");
        assertEquals(UUID_B, drained.get(0).uuid());
    }

    @Test
    void anEntityOfOneDimensionIsInvisibleToTheSameChunkKeyInAnother() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "left-behind");
        accumulator.enterDimension(NETHER);
        accumulator.spawn(2, UUID_B, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "just-arrived");

        List<PacketEntity<String, String, String>> entered = accumulator.dropChunk(NETHER, CHUNK);

        assertEquals(1, entered.size(), "only the entity announced in the dimension being read");
        assertEquals(UUID_B, entered.get(0).uuid());
        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD),
                "the entity of the other dimension is held");
        assertEquals(UUID_A, accumulator.dropChunk(OVERWORLD, CHUNK).get(0).uuid(),
                "and drains under the dimension it was announced in, which is the folder it belongs in");
    }

    @Test
    void aRespawnIntoTheSameDimensionOrphansNothing() {
        // A death sends the same dimension marker a portal does.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");

        accumulator.enterDimension(OVERWORLD);

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD), "the held entity is still this world's");
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size());
    }

    @Test
    void everyDimensionHeldForIsReportedSoTheFinishCanSettleThemSeparately() {
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "this-world");
        accumulator.enterDimension(NETHER);
        accumulator.spawn(2, UUID_B, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "other-world");
        accumulator.spawn(3, new UUID(0, 3), FAR, new EntityPos(0, 0, 0, 0f, 0f), "other-world-elsewhere");

        assertEquals(ImmutableSet.of(OVERWORLD, NETHER), accumulator.heldDimensions(),
                "both worlds are named, so neither can be settled by the other's captured positions");
        assertEquals(ImmutableSet.of(CHUNK, FAR), accumulator.chunks(NETHER), "and each names only its own chunks");
        assertEquals(1, accumulator.dropChunk(NETHER, CHUNK).size(), "draining one leaves the other world alone");
        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD));
        assertEquals(ImmutableSet.of(OVERWORLD, NETHER), accumulator.heldDimensions(),
                "the nether still holds its other chunk, so it is still named");
    }

    @Test
    void aRiderHeldForAnotherDimensionIsNotReHomedToTheVehicle() {
        // A proxy switching the player to another backend server reaches the client as a respawn the download follows,
        // and that server numbers its entities from its own counter, so a vehicle can name a passenger id still held
        // for an entity of the world the player left. Where the two worlds' dimension ids differ, re-homing on the id
        // alone would move that entity to a chunk position taken from the other world.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        // This world's entity is seated under the recycled id, so descending past that id would re-home an
        // entity of this world from a passenger list belonging to another one.
        accumulator.spawn(3, new UUID(0, 3), ELSEWHERE, new EntityPos(0, 0, 0, 0f, 0f), "this world, seated below");
        accumulator.enterDimension(NETHER);
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "recycled id, another world");
        accumulator.recordPassengers(2, new int[] { 3 });

        accumulator.recordPassengers(1, new int[] { 2 });

        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(NETHER),
                "the entity of the other world kept its own chunk");
        assertEquals(ImmutableSet.of(CHUNK, ELSEWHERE), accumulator.chunks(OVERWORLD),
                "and the walk stopped at it rather than reaching through it");
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "so the vehicle drains alone");
    }
}
