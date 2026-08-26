// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableSet;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketSpawnObject;
import net.minecraft.network.play.server.SPacketEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
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
        TestRegistries.bootstrap(); // the spawn payload names an EntityType, which needs the vanilla statics
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

        capture.onMove(2, (short) 0, (short) 0, (short) 0,
                new SPacketEntity.S16PacketEntityLook(2, (byte) 64, (byte) 0, true));

        assertEquals(ImmutableSet.of(farChunk), capture.chunks(OVERWORLD),
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
        capture.onMove(1, (short) (7 * 4096), (short) 0, (short) 0,
                new SPacketEntity.S17PacketEntityLookMove(1, 7 * 4096, 0, 0,
                        (byte) 0, (byte) 0, true));

        assertEquals(19.0, position(capture, 1).x(), "the delta resolves against the held position");
        assertEquals(ImmutableSet.of(ChunkPos.asLong(1, 0)), capture.chunks(OVERWORLD),
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

    private static SPacketSpawnObject addEntity(int id, UUID uuid) {
        // This band's spawn packet is built from the spawned entity and an object-type id, not from an EntityType.
        // These tests store it as an opaque spawn payload without decoding it, so a bare entity spawned as a minecart
        // object stands in.
        Entity source = new PlaceholderEntity();
        source.setEntityId(id);
        source.setUniqueId(uuid);
        return new SPacketSpawnObject(source, 10);
    }

    /** A bare entity double, all the spawn-packet constructor reads from its argument at this band. */
    private static final class PlaceholderEntity extends Entity {
        private PlaceholderEntity() {
            super(null);
        }

        @Override
        protected void entityInit() {}

        @Override
        protected void readEntityFromNBT(NBTTagCompound tag) {}

        @Override
        protected void writeEntityToNBT(NBTTagCompound tag) {}
    }
}
