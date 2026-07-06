// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.SendRangeEstimator;
import world.thearchive.wdl.core.SendRangeSampler;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the MC-typed half of the packet capture: which accumulator call an inbound packet routes to.
 */
class EntityPacketCaptureTest {
    private static final UUID VEHICLE_UUID = new UUID(0, 1);
    private static final UUID RIDER_UUID = new UUID(0, 2);
    private static final String OVERWORLD = "minecraft:overworld";

    @BeforeAll
    static void bootstrapVanillaStatics() {
        TestRegistries.frozen(); // the spawn payload names an EntityType, which needs the vanilla statics
    }

    @Test
    void aRotationOnlyMoveLeavesTheRiderHomedOnItsVehiclesChunk() {
        // A rider's held coordinates stay frozen at where it boarded, so a chunk key recomputed from them reverts
        // the re-home onto its vehicle.
        long boardingChunk = ChunkPos.asLong(0, 0);
        long farChunk = ChunkPos.asLong(4, 0);
        EntityPacketCapture capture = capture();
        capture.spawn(1, VEHICLE_UUID, boardingChunk, entityPos(8.0, 64.0, 8.0), addEntity(1, VEHICLE_UUID));
        capture.spawn(2, RIDER_UUID, boardingChunk, entityPos(8.0, 65.0, 9.0), addEntity(2, RIDER_UUID));
        capture.recordPassengers(1, new int[] { 2 });
        capture.reposition(1, farChunk, entityPos(72.0, 64.0, 8.0));

        capture.onMove(2, new ClientboundMoveEntityPacket.Rot(2, (byte) 64, (byte) 0, true));

        assertEquals(Set.of(farChunk), capture.chunks(OVERWORLD),
                "a head turn must not move the rider back to the chunk it boarded in");
        assertEquals(90f, position(capture, 2).yRot(),
                "the new rotation still has to be recorded, unpacked from the packet's byte turn");
        assertEquals(8.0, position(capture, 2).x(), "a rotation-only packet carries no position to apply");
    }

    @Test
    void aPositionCarryingMoveRehomesTheEntityToTheChunkItEndsIn() {
        EntityPacketCapture capture = capture();
        capture.spawn(1, VEHICLE_UUID, ChunkPos.asLong(0, 0), entityPos(12.0, 64.0, 8.0), addEntity(1, VEHICLE_UUID));

        // A relative move is a delta in 1/4096 of a block off the held position, so this walks seven blocks east,
        // across the chunk border at x=16.
        capture.onMove(1, new ClientboundMoveEntityPacket.PosRot(1, (short) (7 * 4096), (short) 0, (short) 0,
                (byte) 0, (byte) 0, true));

        assertEquals(19.0, position(capture, 1).x(), "the delta resolves against the held position");
        assertEquals(Set.of(ChunkPos.asLong(1, 0)), capture.chunks(OVERWORLD),
                "a move that carries a position re-homes the entity to the chunk it ends in");
    }

    private static EntityPacketCapture capture() {
        return new EntityPacketCapture(false, new SendRangeEstimator(),
                new SendRangeSampler(System::nanoTime, false), OVERWORLD);
    }

    private static EntityPos position(EntityPacketCapture capture, int id) {
        EntityPos pos = capture.positionOf(id);
        if (pos == null) {
            throw new AssertionError("entity " + id + " is not accumulated");
        }
        return pos;
    }

    private static EntityPos entityPos(double x, double y, double z) {
        return new EntityPos(x, y, z, 0f, 0f);
    }

    private static ClientboundAddEntityPacket addEntity(int id, UUID uuid) {
        return new ClientboundAddEntityPacket(id, uuid, 0.0, 64.0, 0.0, 0f, 0f, EntityType.PIG, 0, Vec3.ZERO, 0.0);
    }
}
