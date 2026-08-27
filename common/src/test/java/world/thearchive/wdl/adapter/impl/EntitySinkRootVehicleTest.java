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
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.ChunkPos;
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
 * {@code EntityPlayerMP.writeEntityToNBT}'s own root-vehicle write does, and the captured chest-boat contents fold into
 * it by {@code "Items"}. It also pins the mount persistence restoration, which shares the standalone entity path's
 * {@code applyMobPersistence} seam: {@code PersistenceRequired} is server-authoritative and arrives false on the
 * client, so a name-tagged mount, and any mount under {@code forceMobPersistence}, keeps the stamp or it despawns once
 * the player dismounts in the downloaded world.
 *
 * <p>Uses a real {@link EntityPig} as the mount, a headless {@link EntityPlayer} double as its one-player passenger (a
 * real network-bound player cannot be built headless), and {@link Entity#startRiding} to wire the real riding
 * relationship {@code isBeingRidden}/{@code getRecursivePassengers} read.
 */
class EntitySinkRootVehicleTest {
    private final EntitySink sink = new EntitySinkImpl();
    private final ContainerSink containerSink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    /** A headless player double: a real player passenger cannot be built headless (needs a network connection). */
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
        assertTrue(vehicle.isBeingRidden(), "precondition: a one-player vehicle the entities region refuses");

        assertNull(sink.encodeChunk(ImmutableList.of(vehicle), new ChunkPos(0, 0), false),
                "the chunk path drops it, exactly the loss the RootVehicle capture fixes");

        NBTTagCompound tag = sink.captureRootVehicle(vehicle, false);
        assertNotNull(tag, "captureRootVehicle serializes it regardless, the RootVehicle way vanilla persists a mount");
        assertEquals("minecraft:pig", tag.getString("id"),
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

        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(3, new ItemStack(Items.DIAMOND, 9));
        NBTTagCompound holder = containerSink.captureItems(contents);

        NBTTagCompound folded = containerSink.merge(tag, holder);

        NonNullList<ItemStack> back = NonNullList.withSize(27, ItemStack.EMPTY);
        NBTTagCompound probe = new NBTTagCompound();
        probe.setTag("Items", folded.getTagList("Items", 10));
        ItemStackHelper.loadAllItems(probe, back);
        assertEquals(Items.DIAMOND, back.get(3).getItem(),
                "the captured chest-boat loot lands at its slot in the mount");
        assertEquals(9, back.get(3).getCount());
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
