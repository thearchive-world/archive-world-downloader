// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

/**
 * 1.21.3 entity sink: a client-safe lift of {@code EntityStorage.storeEntities}'s write branch.
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
            if (saveDroppingUnsavableLeashes(entity, entityTag)) {
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
        // a loot-equipped mob, and every mob under forceMobPersistence). A codec reject throws out of save (leash
        // gotcha class); the caller isolates it.
        CompoundTag tag = new CompoundTag();
        if (!saveDroppingUnsavableLeashes(vehicle, tag)) {
            return null;
        }
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag;
    }

    /**
     * Save the entity, first detaching any leash the client cannot save and swapping in a sanitized copy of any
     * equipment or carried-item stack the client cannot save, both from the entity and its passengers. A leash with
     * neither a resolved holder nor a delayed attachment is what vanilla's {@link Leashable.LeashData} codec
     * requireNonNulls on, so {@link Entity#save} throws on it; and because save recurses into passengers, a passenger's
     * unsavable leash aborts the whole vehicle group, dropping a chested mob's container with it. Detaching just those
     * unsavable leash links loses only the leash, mirroring the reconstruct path which leaves an unresolved leash link
     * unset. An item component the disk codec rejects costs either the entity or the whole field being written, so
     * {@link #sanitizeStacks} repairs the stack up front rather than recovering from either outcome. Capture runs on
     * the client main thread, so both kinds of swap are unobservable, and every detached leash and swapped stack is
     * restored before returning so the live entities are unchanged.
     */
    private static boolean saveDroppingUnsavableLeashes(Entity entity, CompoundTag out) {
        List<DetachedLeash> detached = detachIfUnsavable(entity, null);
        List<Runnable> stackRestores = sanitizeStacks(entity, null);
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                detached = detachIfUnsavable(passenger, detached);
                stackRestores = sanitizeStacks(passenger, stackRestores);
            }
        }
        try {
            return entity.save(out);
        } finally {
            if (detached != null) {
                for (DetachedLeash restore : detached) {
                    restore.leashable().setLeashData(restore.leashData());
                }
            }
            if (stackRestores != null) {
                for (Runnable restore : stackRestores) {
                    restore.run();
                }
            }
        }
    }

    /**
     * Swap each of the entity's live stacks the disk codec would reject (equipment on a {@link LivingEntity}, the
     * carried item on an {@link ItemEntity}) for a sanitized copy, returning restores that put the originals back. Only
     * a stack that fails the encode is swapped, so a clean entity is never mutated.
     *
     * <p>Keep {@code ops} lazy: {@code entity.registryAccess()} reaches through the entity's level, so hoisting the
     * build into the wrapper would charge every captured entity for it and would demand a level of entities this method
     * otherwise never touches.
     */
    private static @Nullable List<Runnable> sanitizeStacks(Entity entity, @Nullable List<Runnable> restores) {
        if (entity instanceof LivingEntity living) {
            RegistryOps<Tag> ops = null;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack original = living.getItemBySlot(slot);
                if (original.isEmpty()) {
                    continue;
                }
                if (ops == null) {
                    ops = RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess());
                }
                ItemStack clean = ItemStackSanitizer.sanitizeForSave(original, ops);
                if (clean != original) {
                    living.setItemSlot(slot, clean);
                    restores = addRestore(restores, () -> living.setItemSlot(slot, original));
                }
            }
        } else if (entity instanceof ItemEntity item) {
            ItemStack original = item.getItem();
            if (!original.isEmpty()) {
                RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess());
                ItemStack clean = ItemStackSanitizer.sanitizeForSave(original, ops);
                if (clean != original) {
                    item.setItem(clean);
                    restores = addRestore(restores, () -> item.setItem(original));
                }
            }
        }
        return restores;
    }

    private static List<Runnable> addRestore(@Nullable List<Runnable> restores, Runnable restore) {
        List<Runnable> list = restores != null ? restores : new ArrayList<>();
        list.add(restore);
        return list;
    }

    private static @Nullable List<DetachedLeash> detachIfUnsavable(Entity entity,
            @Nullable List<DetachedLeash> detached) {
        if (entity instanceof Leashable leashable) {
            Leashable.LeashData leashData = leashable.getLeashData();
            // Vanilla's LeashData codec requireNonNulls a leash with neither a resolved holder nor a delayed
            // attachment, so save() throws on exactly that state; a resolvable leash still saves normally
            if (leashData != null && leashData.leashHolder == null && leashData.delayedLeashInfo == null) {
                List<DetachedLeash> list = detached != null ? detached : new ArrayList<>();
                list.add(new DetachedLeash(leashable, leashData));
                leashable.setLeashData(null);
                return list;
            }
        }
        return detached;
    }

    private record DetachedLeash(Leashable leashable, Leashable.LeashData leashData) {}

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
        tag.put("Position", ChunkPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow());
        return tag;
    }
}
