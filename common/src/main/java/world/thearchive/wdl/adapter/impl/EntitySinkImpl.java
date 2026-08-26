// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

/**
 * 1.12.2 entity sink: a client-safe per-entity serialize ({@code Entity.writeToNBTOptional}), the write half of what
 * vanilla's {@code AnvilChunkLoader} folds into a chunk's {@code Level.Entities}. There is no separate
 * {@code entities/} region at this band (that is 1.17 and above), so the writer folds this sink's carrier into the
 * region chunk.
 *
 * <p>Three members (see {@link EntitySink}): {@link #encodeChunk(List, ChunkPos, boolean)} serializes the live client
 * entities, {@link #encodeChunk(List, ChunkPos)} builds the in-chunk {@code Entities} carrier from already-serialized
 * tags (pure, so the headless round-trip guards it), plus {@link #captureRootVehicle(Entity, boolean)}, a single-live
 * gate-bypassing vehicle serialize.
 */
public final class EntitySinkImpl implements EntitySink {
    // 1.12.2 has no Entity.shouldBeSaved(); reproduce its predicate from the primitives it composes: a dead,
    // riding, or single-player-vehicle entity is not written standalone. writeToNBTOptional then repeats the
    // dead/riding half and additionally refuses an entity with no id string (a player), the encode-id gate.
    private static boolean shouldSaveEntity(Entity entity) {
        return !entity.isDead && !entity.isRiding()
                && (!entity.isBeingRidden() || entity.getRecursivePassengersByType(EntityPlayer.class).size() != 1);
    }

    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so each entity tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public @Nullable NBTTagCompound encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence) {
        // Lift of AnvilChunkLoader's entity write branch, server-free: writeToNBTOptional writes the entity NBT
        // straight into a NBTTagCompound with no server touch.
        List<NBTTagCompound> entityTags = new ArrayList<>();
        for (Entity entity : entities) {
            if (!shouldSaveEntity(entity)) {
                continue; // drops passengers (they also nest under their vehicle's Passengers list, so the flat
                         // list would otherwise write them twice), dead entities, and player-only vehicles
            }
            NBTTagCompound entityTag = new NBTTagCompound();
            if (entity.writeToNBTOptional(entityTag)) {
                applyMobPersistence(entityTag, entity, forceMobPersistence);
                entityTags.add(entityTag.copy());
            }
        }
        return encodeChunk(entityTags, pos);
    }

    /**
     * Below 1.15 vanilla {@code ItemStack.writeToNBT} puts the live stack's own {@code tag} compound into its output,
     * so each entity tag is detached before it is handed on: the caller owns it, and the client keeps nothing the
     * map-id remap, the coordinate scrub or the save writer could reach.
     */
    @Override
    public @Nullable NBTTagCompound captureRootVehicle(Entity vehicle, boolean forceMobPersistence) {
        // Vanilla saves a ridden root vehicle via writeToNBTOptional, id-bearing and passenger-recursing (the
        // player passenger self-excludes through writeToNBTAtomically, which refuses an entity with no id string),
        // bypassing the standalone gate (a one-player vehicle fails it). A ridden mount that finishes then gets
        // dismounted in the downloaded world despawns exactly like any other captured mob, so it gets the same
        // server-side PersistenceRequired restoration the standalone entity path applies via applyMobPersistence
        // (a named mob, a loot-equipped mob, and every mob under forceMobPersistence).
        NBTTagCompound tag = new NBTTagCompound();
        if (!vehicle.writeToNBTOptional(tag)) {
            return null;
        }
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag.copy();
    }

    /**
     * Restore the server-authoritative {@code PersistenceRequired} the client never receives. Two vanilla mechanisms
     * set it that the capture can reconstruct: the name-tag item sets it on any {@link EntityLiving} it renames, and
     * {@code EntityLiving.setItemStackToSlot} plus the drop-chance flag set it when a mob equips a picked-up item. A
     * named mob and a mob whose equipment proves such a pickup (an item impossible for its natural spawn,
     * {@link NaturalEquipment#wasLootEquipped}) are both stamped unconditionally, since either is a lossless
     * correctness restoration. With {@code forceMobPersistence} on, every captured {@link EntityLiving} is stamped too.
     * A non-mob entity is never touched.
     */
    static void applyMobPersistence(NBTTagCompound entityTag, boolean isMob, boolean namedMob,
            boolean derivedPickup, boolean forceMobPersistence) {
        if (namedMob || derivedPickup || (isMob && forceMobPersistence)) {
            entityTag.setBoolean("PersistenceRequired", true);
        }
    }

    /**
     * Derive the persistence flags from a live {@code entity} (mob-ness, custom name, loot-equipped state) and apply
     * them, so the standalone entity path and the ridden-mount path stamp persistence identically without duplicating
     * the derivation.
     */
    static void applyMobPersistence(NBTTagCompound entityTag, Entity entity, boolean forceMobPersistence) {
        boolean isMob = entity instanceof EntityLiving;
        boolean namedMob = false;
        boolean derivedPickup = false;
        if (entity instanceof EntityLiving) {
            EntityLiving mob = (EntityLiving) entity;
            namedMob = mob.hasCustomName();
            derivedPickup = NaturalEquipment.wasLootEquipped(mob);
        }
        applyMobPersistence(entityTag, isMob, namedMob, derivedPickup, forceMobPersistence);
    }

    @Override
    public @Nullable NBTTagCompound encodeChunk(List<NBTTagCompound> entityTags, ChunkPos pos) {
        if (entityTags.isEmpty()) {
            return null;
        }
        NBTTagList entities = new NBTTagList();
        for (NBTTagCompound entityTag : entityTags) {
            entities.appendTag(entityTag);
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Entities", entities);
        return tag;
    }
}
