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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
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

/**
 * The automated guard for the ridden-vehicle RootVehicle capture. A vehicle carrying exactly one player is refused by
 * the entities region (vanilla persists it in the player's own {@code RootVehicle}, not the entities region), so the
 * chunk path drops it. {@link EntitySink#captureRootVehicle} is the sibling that serializes it anyway, the way
 * {@code ServerPlayer.saveParentVehicle} does with {@code root.save}, and the captured chest-boat contents fold into it
 * by {@code "Items"}. It also pins the mount persistence restoration, which shares the standalone entity path's
 * {@code applyMobPersistence} seam: {@code PersistenceRequired} is server-authoritative and arrives false on the
 * client, so a name-tagged mount, and any mount under {@code forceMobPersistence}, keeps the stamp or it despawns once
 * the player dismounts in the downloaded world.
 *
 * <p>Uses two headless doubles: an {@link Entity} that reports as a one-player vehicle (a real player passenger cannot
 * be built headless), and a {@link Mob} for the branch the restoration is gated on.
 */
class EntitySinkRootVehicleTest {
    private final EntitySink sink = new EntitySinkImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void captureRootVehicleSerializesTheOnePlayerVehicleTheChunkPathDrops() {
        TestRegistries.bootstrap();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();
        assertTrue(vehicle.isVehicle() && vehicle.hasOnePlayerPassenger(),
                "precondition: a one-player vehicle the entities region refuses");

        assertNull(sink.encodeChunk(ImmutableList.of(vehicle), new ChunkPos(0, 0), false),
                "the chunk path drops it, exactly the loss the RootVehicle capture fixes");

        CompoundTag tag = sink.captureRootVehicle(vehicle, false);
        assertNotNull(tag, "captureRootVehicle serializes it regardless, the RootVehicle way vanilla persists a mount");
        assertEquals("minecraft:pig", tag.getString("id"),
                "the vehicle NBT carries its type id so loadAndSpawnParentVehicle can respawn it");
    }

    @Test
    void capturedContentsFoldIntoTheMountTagUnderItems() {
        TestRegistries.bootstrap();
        RiddenVehicleEntity vehicle = new RiddenVehicleEntity();

        CompoundTag tag = sink.captureRootVehicle(vehicle, false);
        assertNotNull(tag);
        assertEquals(0, tag.getList("Items", 10).size(),
                "the captured mount serializes no Items of its own, so the fold is required");

        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(3, new ItemStack(Items.DIAMOND, 9));
        CompoundTag holder = containerSink.captureItems(contents);

        CompoundTag folded = containerSink.merge(tag, holder);

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        CompoundTag probe = new CompoundTag();
        probe.put("Items", folded.getList("Items", 10));
        ContainerHelper.loadAllItems(probe, back);
        assertEquals(Items.DIAMOND, back.get(3).getItem(),
                "the captured chest-boat loot lands at its slot in the mount");
        assertEquals(9, back.get(3).getCount());
    }

    @Test
    void namedRootMountKeepsTheServerPersistenceTheClientNeverCarries() {
        TestRegistries.bootstrap();
        RiddenMountMob mount = new RiddenMountMob();
        mount.setCustomName(new TextComponent("Rocinante"));

        CompoundTag tag = sink.captureRootVehicle(mount, false);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "a name-tagged mount is persistence-required server-side, a flag the client entity arrives without, "
                        + "so the capture must restore it or the mount despawns once the player dismounts");
    }

    @Test
    void unnamedRootMountKeepsVanillaDespawnBehavior() {
        TestRegistries.bootstrap();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, false);
        assertNotNull(tag);
        assertFalse(tag.getBoolean("PersistenceRequired"),
                "an un-named mount has nothing proving server persistence, so it keeps vanilla despawn behavior");
    }

    @Test
    void unnamedRootMountPersistsUnderTheForceKnob() {
        TestRegistries.bootstrap();
        RiddenMountMob mount = new RiddenMountMob();

        CompoundTag tag = sink.captureRootVehicle(mount, true);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "with forceMobPersistence set every captured mob keeps PersistenceRequired, named or not, so the "
                        + "un-named mount threads the knob through the same seam the standalone entity path uses");
    }

    /** A headless vehicle double the entities region refuses, standing in for a boat carrying one player. */
    private static final class RiddenVehicleEntity extends Entity {
        private RiddenVehicleEntity() {
            // Below 1.16 Entity.saveWithoutId writes this.dimension.getId(), which the constructor sets only from a
            // non-null level, so the double is built against the HeadlessLevel rather than a null one.
            super(EntityType.PIG, HeadlessLevel.get());
        }

        // 1.16.5 has no Entity.shouldBeSaved; a one-player vehicle is refused by the isVehicle and
        // hasOnePlayerPassenger primitives EntitySinkImpl reproduces that predicate from, forced true here since a
        // real player passenger cannot be built headless.
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

    /** A headless mount double on the {@link Mob} branch, where the persistence restoration applies. */
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
