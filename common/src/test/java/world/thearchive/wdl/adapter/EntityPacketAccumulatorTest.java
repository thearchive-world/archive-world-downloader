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

/**
 * The headless guard for the production packet-state accumulator: inbound entity packet state is keyed by the int
 * entity id (the packet-layer key every post-spawn packet uses), bridged to the save-layer UUID, and held independent
 * of unload (a client removal is DISCARDED and tells you nothing, so there is no eviction at all). Beyond the spawn and
 * synced-data state proven for item frames, the generalization to every non-player type holds the post-spawn packets
 * too: position (so a moving entity re-homes to the chunk it ends in), equipment (merged by slot), passengers, and the
 * leash holder. State is drained one chunk at a time so the main thread can reconstruct and write whole chunks as they
 * leave the keep-hot window. Generic over the spawn/synced/equipment payloads so the bookkeeping is MC-free; the
 * production specialization binds the MC packet types.
 *
 * <p>Every read is scoped to one dimension, which is the other half of the key: chunk positions are shared between
 * dimensions, and the accumulator outlives a dimension change, so a bare chunk key is ambiguous exactly when the
 * download is most able to act on the wrong answer.
 */
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
        // The spawn position is held so a never-moved entity reconstructs where it appeared; rotation rides too.
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
        // SetEntityData carries a diff keyed by the accessor id; a later value for an accessor overwrites the
        // earlier (vanilla's assignValues semantics), so a frame whose item changes ends at the latest item.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn-packet");
        accumulator.recordData(7, 8, "item=apple");
        accumulator.recordData(7, 9, "rotation=2");
        accumulator.recordData(7, 8, "item=sword"); // the framed item was swapped

        List<String> synced = accumulator.dropChunk(OVERWORLD, CHUNK).get(0).synced();

        assertEquals(2, synced.size(), "one value per accessor id, not one per packet");
        assertTrue(synced.contains("item=sword"), "the latest value for an accessor wins");
        assertTrue(synced.contains("rotation=2"));
        assertFalse(synced.contains("item=apple"), "the superseded value is gone");
    }

    @Test
    void equipmentMergesBySlotWithTheLatestWinning() {
        // SetEquipment is applied per slot the way the client does (setItemSlot), so a re-equip of one slot
        // overwrites only that slot; the merge mirrors the synced diff but on its own channel.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");
        accumulator.recordEquipment(7, 0, "head=helmet");
        accumulator.recordEquipment(7, 1, "chest=plate");
        accumulator.recordEquipment(7, 0, "head=crown"); // the head slot was re-equipped

        List<String> equipment = accumulator.dropChunk(OVERWORLD, CHUNK).get(0).equipment();

        assertEquals(2, equipment.size(), "one value per slot, not one per packet");
        assertTrue(equipment.contains("head=crown"), "the latest value for a slot wins");
        assertTrue(equipment.contains("chest=plate"));
        assertFalse(equipment.contains("head=helmet"), "the superseded slot value is gone");
    }

    @Test
    void passengersAreReplacedWholesale() {
        // SetPassengers is the full passenger list (the client ejects then re-seats), so a later packet replaces
        // the prior list rather than merging into it.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.recordPassengers(7, new int[] { 10, 11 });
        accumulator.recordPassengers(7, new int[] { 12 }); // re-seated

        assertArrayEquals(new int[] { 12 }, accumulator.dropChunk(OVERWORLD, CHUNK).get(0).passengers());
    }

    @Test
    void theLeashHolderIsRecordedAndDefaultsToNone() {
        // SetEntityLink carries the holder int id; absent any link the holder is 0, matching vanilla's
        // delayedLeashHolderId != 0 has-a-leash convention.
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
        // Movement is the gap the generalization closes: an item frame never moves, but a mob walks. Its chunk
        // must follow the move/teleport/position-sync so the privacy gate and the drain key on where it ends.
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
        // The MC specialization reads this to resolve a relative move (a delta off the current base) into
        // an absolute position before re-homing; an untracked id yields null so a stray move is ignored.
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
        // A post-spawn packet can arrive for an id we never spawned (a non-tracked entity, or one whose spawn we
        // missed); none of them may conjure an entry with no spawn payload to reconstruct from.
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
        // The tee gates the per-value SetEntityData merge on this so a non-tracked entity's synced update costs
        // the Netty thread one lookup, not one per value (the Netty path does almost nothing).
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "spawn-packet");

        assertTrue(accumulator.tracks(7), "a spawned id is tracked");
        assertFalse(accumulator.tracks(8), "an unspawned id is not");
    }

    @Test
    void drainingOneChunkLeavesTheOthersIntact() {
        // Whole-chunk drain is how the main thread writes a chunk's entities as it leaves the keep-hot window;
        // it must take only that chunk, so a still-hot chunk's held entities are not written early or lost.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "in-chunk");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "other-chunk");

        assertEquals(ImmutableSet.of(CHUNK, FAR), accumulator.chunks(OVERWORLD));
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "only the drained chunk");
        assertEquals(ImmutableSet.of(FAR), accumulator.chunks(OVERWORLD), "the other chunk is still held");
    }

    @Test
    void settingPassengersReHomesTheRidersToTheVehiclesChunk() {
        // A rider is positioned client-side at its vehicle, so the server need not move it; left alone its
        // accumulated chunk would lag the vehicle and they would drain in different batches and fail to nest.
        // Re-homing each tracked rider to the vehicle's chunk keeps them together for a whole-chunk drain.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "rider"); // a different chunk at spawn

        accumulator.recordPassengers(1, new int[] { 2 });

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD), "the rider joined the vehicle's chunk");
        assertEquals(2, accumulator.dropChunk(OVERWORLD, CHUNK).size(), "vehicle and rider drain in one batch");
    }

    @Test
    void movingTheVehicleCarriesItsRidersToTheNewChunk() {
        // When the vehicle re-homes on a move, its riders follow, so they stay in one drain batch and keep
        // nesting after the vehicle has driven across a chunk boundary.
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
        // SetPassengers can arrive before the rider's AddEntity (out of order on the wire). reHomePassengers
        // skips a not-yet-tracked rider id, and the later rider spawn is not retroactively re-homed, so the rider
        // keeps its own chunk and saves standalone instead of nested. No data loss (the rider is still saved);
        // pinned here as the documented edge, not a fix (a retroactive re-home would need a
        // reverse rider-to-vehicle index for a non-loss case). The in-order spawn-then-SetPassengers case nests,
        // covered by settingPassengersReHomesTheRidersToTheVehiclesChunk.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "vehicle");
        accumulator.recordPassengers(1, new int[] { 2 }); // rider id 2 not spawned yet: the re-home finds nothing
        accumulator.spawn(2, UUID_B, FAR, new EntityPos(0, 0, 0, 0f, 0f), "rider"); // arrives later, a different chunk

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
        // Each list is one id long, which is the shape that lets a worklist wrapping the held array rather than
        // copying it overwrite the vehicle's own passengers in place with its rider's.
        assertArrayEquals(new int[] { 2 }, passengersOf(drained, 1), "the walk left the vehicle's own list alone");
        assertArrayEquals(new int[] { 3 }, passengersOf(drained, 2), "and the mount's, which it walked through");
    }

    @Test
    void everyRiderOfOneVehicleFollowsIt() {
        // A vehicle seats more than one (a boat holds two), so a first-rider-only walk strands the rest.
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
        // The server reuses an int id once the prior entity at it is gone. A fresh spawn for that id is a new
        // identity (new UUID), and all its post-spawn state must start empty: the old entity's item, equipment,
        // passengers, and leash must not bleed into the new one. This is also why no removal handling is needed,
        // a new spawn supersedes by id.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(7, UUID_A, CHUNK, new EntityPos(1, 1, 1, 0f, 0f), "old");
        accumulator.recordData(7, 8, "item=apple");
        accumulator.recordEquipment(7, 0, "head=helmet");
        accumulator.recordPassengers(7, new int[] { 10 });
        accumulator.recordLeash(7, 42);
        accumulator.spawn(7, UUID_B, CHUNK, new EntityPos(2, 2, 2, 0f, 0f), "new"); // id 7 reused

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
        // No eviction exists by design, so a server minting fresh ids forever (a spawn flood, or hours at a
        // farm with the player stationary and the hot chunks never draining) would otherwise grow the map
        // without bound. At the ceiling a NEW id is skipped, counted, and never drained; a legitimate capture
        // never reaches the ceiling. The fed-spawn count still ticks so the reconciliation stays honest about
        // what arrived.
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
        // Replacement does not grow the map, so the id-reuse supersede semantics must survive the ceiling:
        // blocking it would freeze stale state for an id the server has already recycled.
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
        // The whole position space is shared between dimensions, so the same key names a chunk in each. A read
        // that ignored the dimension would hand the world the player entered an entity of the world they left,
        // to be written at that key into the wrong folder; the collision is not exotic, since a nether key is
        // one eighth the magnitude of the overworld coordinates it maps to and lands where a player who
        // explored spawn has already captured.
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
        // A death sends the same dimension marker a portal does. Keying on the world rather than on a count of
        // markers is what keeps a death from orphaning every entity held around the player.
        EntityPacketAccumulator<String, String, String> accumulator = accumulator();
        accumulator.spawn(1, UUID_A, CHUNK, new EntityPos(0, 0, 0, 0f, 0f), "mob");

        accumulator.enterDimension(OVERWORLD);

        assertEquals(ImmutableSet.of(CHUNK), accumulator.chunks(OVERWORLD), "the held entity is still this world's");
        assertEquals(1, accumulator.dropChunk(OVERWORLD, CHUNK).size());
    }

    @Test
    void everyDimensionHeldForIsReportedSoTheFinishCanSettleThemSeparately() {
        // The finish has to ask a per-dimension question of what it is holding, namely whether THAT dimension
        // captured the terrain the entity stands on, which decides whether the drop is the privacy gate doing
        // its job or an entity that was writable and went unwritten. A bulk drain could not ask it.
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
        // Ids are recycled across a dimension change, so a vehicle of one world can name a passenger id the
        // stream has since given to an entity of another. Re-homing on the id alone would move that entity into
        // the other world's chunk, where it would drain and be written under the wrong dimension.
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
