// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
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
        TestRegistries.bootstrap();
    }

    private static final class HeadlessPlayer extends EntityPlayer {
        HeadlessPlayer() {
            super(HeadlessLevel.get(), new GameProfile(UUID.randomUUID(), "wdl-test"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }

    private static EntityPig riddenByOnePlayer() {
        EntityPig vehicle = new EntityPig(HeadlessLevel.get());
        new HeadlessPlayer().startRiding(vehicle);
        return vehicle;
    }

    @Test
    void captureRootVehicleSerializesTheOnePlayerVehicleTheChunkPathDrops() {
        TestRegistries.bootstrap();
        EntityPig vehicle = riddenByOnePlayer();
        assertTrue(vehicle.isBeingRidden(), "precondition: a vehicle carrying exactly one player");

        assertNull(sink.encodeChunk(ImmutableList.of(vehicle), new ChunkPos(0, 0), false),
                "the chunk path drops it, exactly the loss the RootVehicle capture fixes");

        NBTTagCompound tag = sink.captureRootVehicle(vehicle, false);
        assertNotNull(tag, "captureRootVehicle serializes it regardless, the RootVehicle way vanilla persists a mount");
        assertEquals("Pig", tag.getString("id"),
                "the vehicle NBT carries its type id so loadAndSpawnParentVehicle can respawn it");
    }

    @Test
    void capturedContentsFoldIntoTheMountTagUnderItems() {
        TestRegistries.bootstrap();
        EntityPig vehicle = riddenByOnePlayer();

        NBTTagCompound tag = sink.captureRootVehicle(vehicle, false);
        assertNotNull(tag);
        assertEquals(0, tag.getTagList("Items", 10).tagCount(),
                "the captured mount serializes no Items of its own, so the fold is required");

        ItemStack[] contents = new ItemStack[27];
        contents[3] = new ItemStack(Items.DIAMOND, 9);
        NBTTagCompound holder = containerSink.captureItems(contents);

        NBTTagCompound folded = containerSink.merge(tag, holder);

        ItemStack[] back = new ItemStack[27];
        NBTTagCompound probe = new NBTTagCompound();
        probe.setTag("Items", folded.getTagList("Items", 10));
        ItemListNbt.loadAllItems(probe, back);
        assertEquals(Items.DIAMOND, back[3].getItem(), "the captured container loot lands at its slot in the mount");
        assertEquals(9, back[3].stackSize);
    }

    @Test
    void namedRootMountKeepsTheServerPersistenceTheClientNeverCarries() {
        TestRegistries.bootstrap();
        EntityPig mount = new EntityPig(HeadlessLevel.get());
        mount.setCustomNameTag("Rocinante");

        NBTTagCompound tag = sink.captureRootVehicle(mount, false);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "a name-tagged mount is persistence-required server-side, a flag the client entity arrives without, "
                        + "so the capture must restore it or the mount despawns once the player dismounts");
    }

    @Test
    void unnamedRootMountKeepsVanillaDespawnBehavior() {
        TestRegistries.bootstrap();
        EntityPig mount = new EntityPig(HeadlessLevel.get());

        NBTTagCompound tag = sink.captureRootVehicle(mount, false);
        assertNotNull(tag);
        assertFalse(tag.getBoolean("PersistenceRequired"),
                "an un-named mount has nothing proving server persistence, so it keeps vanilla despawn behavior");
    }

    @Test
    void unnamedRootMountPersistsUnderTheForceKnob() {
        TestRegistries.bootstrap();
        EntityPig mount = new EntityPig(HeadlessLevel.get());

        NBTTagCompound tag = sink.captureRootVehicle(mount, true);
        assertNotNull(tag);
        assertTrue(tag.getBoolean("PersistenceRequired"),
                "with forceMobPersistence set every captured mob keeps PersistenceRequired, named or not, so the "
                        + "un-named mount threads the knob through the same seam the standalone entity path uses");
    }
}
