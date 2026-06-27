// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The deterministic guard for the seated position anchor: a seated player anchors to its vehicle, a standing player to
 * the camera anchor. This pins the decision that the anchor keys on {@code isPassenger()}, not on whether a RootVehicle
 * was written, so a regression re-keying it is caught headless rather than only at the live gate.
 */
class CaptureAnchorTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void seatedPlayerAnchorsToTheRootVehicle() {
        BareEntity player = new BareEntity();
        BareEntity vehicle = new BareEntity();
        BareEntity camera = new BareEntity();
        seat(player, vehicle);

        assertSame(vehicle, LiveCaptureSession.captureAnchor(player, camera),
                "a seated player anchors to its root vehicle, not the camera anchor");
    }

    @Test
    void standingPlayerAnchorsToTheCameraAnchor() {
        BareEntity player = new BareEntity();
        BareEntity camera = new BareEntity();

        assertSame(camera, LiveCaptureSession.captureAnchor(player, camera),
                "a standing player keeps the ordinary camera anchor");
    }

    private static void seat(Entity rider, Entity vehicle) {
        try {
            Field vehicleField = Entity.class.getDeclaredField("vehicle");
            vehicleField.setAccessible(true);
            vehicleField.set(rider, vehicle);
            Field passengersField = Entity.class.getDeclaredField("passengers");
            passengersField.setAccessible(true);
            passengersField.set(vehicle, ImmutableList.of(rider));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not seat the test rider", e);
        }
    }

    private static final class BareEntity extends Entity {
        private BareEntity() {
            super(EntityType.PIG, null);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(ValueInput input) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {}

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }
}
