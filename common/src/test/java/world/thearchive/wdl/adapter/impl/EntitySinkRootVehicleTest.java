// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ContainerSink;
import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

class EntitySinkRootVehicleTest {
    private final EntitySink sink = new EntitySinkImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void captureRootVehicleSerializesTheOnePlayerVehicleTheChunkPathDrops() {
        RegistryAccess registries = TestRegistries.frozen();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();
        assertTrue(vehicle.isVehicle() && vehicle.hasOnePlayerPassenger(),
                "precondition: a vehicle carrying exactly one player");

        assertNull(sink.encodeChunk(ImmutableList.of(vehicle), new ChunkPos(0, 0), registries, false),
                "the chunk path drops it, exactly the loss the RootVehicle capture fixes");

        CompoundTag tag = sink.captureRootVehicle(vehicle, registries, false);
        assertNotNull(tag, "captureRootVehicle serializes it regardless, the RootVehicle way vanilla persists a mount");
        assertEquals("minecraft:pig", tag.getString("id"),
                "the vehicle NBT carries its type id so loadAndSpawnParentVehicle can respawn it");
    }

    @Test
    void capturedContentsFoldIntoTheMountTagUnderItems() {
        RegistryAccess registries = TestRegistries.frozen();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();

        CompoundTag tag = sink.captureRootVehicle(vehicle, registries, false);
        assertNotNull(tag);
        assertEquals(0, tag.getList("Items", 10).size(),
                "the captured mount serializes no Items of its own, so the fold is required");

        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(3, new ItemStack(Items.DIAMOND, 9));
        CompoundTag holder = containerSink.captureItems(contents, registries);

        CompoundTag folded = containerSink.merge(tag, holder);

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        CompoundTag probe = new CompoundTag();
        probe.put("Items", folded.getList("Items", 10));
        ContainerHelper.loadAllItems(probe, back);
        assertEquals(Items.DIAMOND, back.get(3).getItem(),
                "the captured container loot lands at its slot in the mount");
        assertEquals(9, back.get(3).getCount());
    }

    @Test
    void namedRootMountKeepsTheServerPersistenceTheClientNeverCarries() {
        RegistryAccess registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();
        mount.setCustomName(new TextComponent("Rocinante"));

        CompoundTag tag = sink.captureRootVehicle(mount, registries, false);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "a name-tagged mount is persistence-required server-side, a flag the client entity arrives without, "
                        + "so the capture must restore it or the mount despawns once the player dismounts");
    }

    @Test
    void unnamedRootMountKeepsVanillaDespawnBehavior() {
        RegistryAccess registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, registries, false);
        assertNotNull(tag);
        assertFalse(tag.getBoolean("PersistenceRequired"),
                "an un-named mount has nothing proving server persistence, so it keeps vanilla despawn behavior");
    }

    @Test
    void unnamedRootMountPersistsUnderTheForceKnob() {
        RegistryAccess registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, registries, true);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "with forceMobPersistence set every captured mob keeps PersistenceRequired, named or not, so the "
                        + "un-named mount threads the knob through the same seam the standalone entity path uses");
    }

    private static final class RiddenVehicleEntity extends Entity {
        private RiddenVehicleEntity() {
            super(EntityType.PIG, null);
        }

        @Override
        public Packet<?> getAddEntityPacket() {
            return new ClientboundAddEntityPacket(this);
        }

        @Override
        public boolean isVehicle() {
            return true;
        }

        @Override
        public boolean hasOnePlayerPassenger() {
            return true;
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {
            // writes no save data, mirroring a live chest mount whose menu-only contents are absent from its serialize
        }

        @Override
        protected void defineSynchedData() {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return false;
        }
    }

    private static final class RiddenMountMob extends Mob {
        private RiddenMountMob() {
            super(EntityType.PIG, HeadlessLevel.get());
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return false;
        }
    }
}
