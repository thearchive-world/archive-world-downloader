// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.ContainerSink;
import world.thearchive.wdl.adapter.EntitySink;
import world.thearchive.wdl.testsupport.HeadlessLevel;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the ridden-vehicle RootVehicle capture. A vehicle carrying exactly one player is refused by
 * {@code Entity.shouldBeSaved()} (vanilla persists it in the player's own {@code RootVehicle}, not the entities
 * region), so the chunk path drops it. {@link EntitySink#captureRootVehicle} is the sibling that serializes it anyway,
 * the way {@code ServerPlayer.saveParentVehicle} does with {@code root.save}, and the captured chest-boat contents fold
 * into it by {@code "Items"}. It also pins the mount persistence restoration, which shares the standalone entity path's
 * {@code applyMobPersistence} seam: {@code PersistenceRequired} is server-authoritative and arrives false on the
 * client, so a name-tagged mount, and any mount under {@code forceMobPersistence}, keeps the stamp or it despawns once
 * the player dismounts in the downloaded world.
 *
 * <p>Uses two headless doubles: an {@link Entity} whose {@code shouldBeSaved()} is forced false (a real player
 * passenger cannot be built headless), and a {@link Mob} for the branch the restoration is gated on.
 */
class EntitySinkRootVehicleTest {
    private final EntitySink sink = new EntitySinkImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen();
    }

    @Test
    void captureRootVehicleSerializesTheOnePlayerVehicleTheChunkPathDrops() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();
        assertFalse(vehicle.shouldBeSaved(), "precondition: a one-player vehicle the entities region refuses");

        assertNull(sink.encodeChunk(List.of(vehicle), new ChunkPos(0, 0), registries, false),
                "the chunk path drops it, exactly the loss the RootVehicle capture fixes");

        CompoundTag tag = sink.captureRootVehicle(vehicle, registries, false);
        assertNotNull(tag, "captureRootVehicle serializes it regardless, the RootVehicle way vanilla persists a mount");
        assertEquals("minecraft:pig", tag.getStringOr("id", ""),
                "the vehicle NBT carries its type id so loadAndSpawnParentVehicle can respawn it");
    }

    @Test
    void capturedContentsFoldIntoTheMountTagUnderItems() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();

        CompoundTag tag = sink.captureRootVehicle(vehicle, registries, false);
        assertNotNull(tag);
        assertEquals(0, tag.getListOrEmpty("Items").size(),
                "the captured mount serializes no Items of its own, so the fold is required");

        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(3, new ItemStack(Items.DIAMOND, 9));
        CompoundTag holder = containerSink.captureItems(contents, registries);

        CompoundTag folded = containerSink.merge(tag, holder);

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        CompoundTag probe = new CompoundTag();
        probe.put("Items", folded.getListOrEmpty("Items"));
        ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, registries, probe), back);
        assertEquals(Items.DIAMOND, back.get(3).getItem(),
                "the captured chest-boat loot lands at its slot in the mount");
        assertEquals(9, back.get(3).getCount());
    }

    @Test
    void namedRootMountKeepsTheServerPersistenceTheClientNeverCarries() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();
        mount.setCustomName(Component.literal("Rocinante"));

        CompoundTag tag = sink.captureRootVehicle(mount, registries, false);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired").orElse(false),
                "a name-tagged mount is persistence-required server-side, a flag the client entity arrives without, "
                        + "so the capture must restore it or the mount despawns once the player dismounts");
    }

    @Test
    void unnamedRootMountKeepsVanillaDespawnBehavior() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, registries, false);
        assertNotNull(tag);
        assertFalse(tag.getBoolean("PersistenceRequired").orElse(false),
                "an un-named mount has nothing proving server persistence, so it keeps vanilla despawn behavior");
    }

    @Test
    void unnamedRootMountPersistsUnderTheForceKnob() {
        RegistryAccess.Frozen registries = TestRegistries.frozen();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, registries, true);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired").orElse(false),
                "with forceMobPersistence set every captured mob keeps PersistenceRequired, named or not, so the "
                        + "un-named mount threads the knob through the same seam the standalone entity path uses");
    }

    /** A headless vehicle double the entities region refuses, standing in for a boat carrying one player. */
    private static final class RiddenVehicleEntity extends Entity {
        private RiddenVehicleEntity() {
            super(EntityTypes.PIG, HeadlessLevel.get());
        }

        @Override
        public boolean shouldBeSaved() {
            return false; // a vehicle carrying exactly one player, the state saveParentVehicle owns
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
            // writes no save data, mirroring a live chest mount whose menu-only contents are absent from its serialize
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(ValueInput input) {}

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }

    /** A headless mount double on the {@link Mob} branch, where the persistence restoration applies. */
    private static final class RiddenMountMob extends Mob {
        private RiddenMountMob() {
            super(EntityTypes.PIG, HeadlessLevel.get());
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }
}
