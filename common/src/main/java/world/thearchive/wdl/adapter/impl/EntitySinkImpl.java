// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

/**
 * 1.19.4 entity sink: a client-safe lift of {@code EntityStorage.storeEntities}'s write branch.
 *
 * <p>Three members (see {@link EntitySink}): {@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)} serializes
 * the live client entities, {@link #encodeChunk(List, ChunkPos)} builds the entities-region envelope from
 * already-serialized tags (pure, so the headless round-trip guards it), plus
 * {@link #captureRootVehicle(Entity, RegistryAccess, boolean)}, a single-live gate-bypassing vehicle serialize.
 */
public final class EntitySinkImpl implements EntitySink {
    @Override
    public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, RegistryAccess registries,
            boolean forceMobPersistence) {
        // Lift of EntityStorage.storeEntities' write branch, server-free: entity.save writes the entity NBT
        // straight into a CompoundTag with no ServerLevel touch.
        List<CompoundTag> entityTags = new ArrayList<>();
        for (Entity entity : entities) {
            if (!entity.shouldBeSaved()) {
                continue; // drops passengers (they also nest under their vehicle's Passengers list, so the flat
                         // list would otherwise write them twice), removed entities, and player-only vehicles
            }
            CompoundTag entityTag = new CompoundTag();
            if (entity.save(entityTag)) {
                applyMobPersistence(entityTag, entity, forceMobPersistence);
                entityTags.add(entityTag);
            }
        }
        return encodeChunk(entityTags, pos);
    }

    @Override
    public @Nullable CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess registries,
            boolean forceMobPersistence) {
        // Vanilla saveParentVehicle serializes the root vehicle via root.save, id-bearing and passenger-recursing,
        // bypassing shouldBeSaved (a one-player vehicle fails it). A ridden mount that finishes then gets dismounted
        // in the downloaded world despawns exactly like any other captured mob, so it gets the same server-side
        // PersistenceRequired restoration the standalone entity path applies via applyMobPersistence (a named mob,
        // a loot-equipped mob, and every mob under forceMobPersistence).
        CompoundTag tag = new CompoundTag();
        if (!vehicle.save(tag)) {
            return null;
        }
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag;
    }

    /**
     * Restore the server-authoritative {@code PersistenceRequired} the client never receives. Two vanilla mechanisms
     * set it that the capture can reconstruct: the NameTagItem sets it on any {@link Mob} it renames, and
     * {@code Mob.setItemSlotAndDropWhenKilled} sets it when a mob equips a picked-up item. A named mob and a mob whose
     * equipment proves such a pickup (an item impossible for its natural spawn,
     * {@link NaturalEquipment#wasLootEquipped}) are both stamped unconditionally, since either is a lossless
     * correctness restoration. With {@code forceMobPersistence} on, every captured {@link Mob} is stamped too. A
     * non-Mob entity is never touched.
     */
    static void applyMobPersistence(CompoundTag entityTag, boolean isMob, boolean namedMob,
            boolean derivedPickup, boolean forceMobPersistence) {
        if (namedMob || derivedPickup || (isMob && forceMobPersistence)) {
            entityTag.putBoolean("PersistenceRequired", true);
        }
    }

    /**
     * Derive the persistence flags from a live {@code entity} (Mob-ness, custom name, loot-equipped state) and apply
     * them, so the standalone entity path and the ridden-mount path stamp persistence identically without duplicating
     * the derivation.
     */
    static void applyMobPersistence(CompoundTag entityTag, Entity entity, boolean forceMobPersistence) {
        boolean isMob = entity instanceof Mob;
        boolean namedMob = false;
        boolean derivedPickup = false;
        if (entity instanceof Mob mob) {
            namedMob = mob.hasCustomName();
            derivedPickup = NaturalEquipment.wasLootEquipped(mob);
        }
        applyMobPersistence(entityTag, isMob, namedMob, derivedPickup, forceMobPersistence);
    }

    @Override
    public @Nullable CompoundTag encodeChunk(List<CompoundTag> entityTags, ChunkPos pos) {
        if (entityTags.isEmpty()) {
            return null; // skip empty entity-chunks: capture omits them (vanilla writes IOWorker.STORE_EMPTY)
        }
        ListTag entities = new ListTag();
        for (CompoundTag entityTag : entityTags) {
            entities.add(entityTag);
        }
        CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.put("Entities", entities);
        tag.put("Position", new IntArrayTag(new int[] { pos.x, pos.z }));
        return tag;
    }
}
