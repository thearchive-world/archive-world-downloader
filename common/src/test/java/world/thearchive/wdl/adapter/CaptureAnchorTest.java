// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The deterministic guard for the seated position anchor: a seated player anchors to its vehicle, a standing player to
 * the camera anchor. This pins the decision that the anchor keys on {@code isRiding()}, not on whether a RootVehicle
 * was written, so a regression re-keying it is caught headless rather than only at the live gate.
 */
class CaptureAnchorTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
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
            Field ridingEntityField = Entity.class.getDeclaredField("ridingEntity");
            ridingEntityField.setAccessible(true);
            ridingEntityField.set(rider, vehicle);
            Field riddenByEntitiesField = Entity.class.getDeclaredField("riddenByEntities");
            riddenByEntitiesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Entity> riddenBy = (List<Entity>) riddenByEntitiesField.get(vehicle);
            riddenBy.add(rider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not seat the test rider", e);
        }
    }

    private static final class BareEntity extends Entity {
        private BareEntity() {
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
