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
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

public final class EntitySinkImpl implements EntitySink {
    @Override
    public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, RegistryAccess registries,
            boolean forceMobPersistence) {
        List<CompoundTag> entityTags = new ArrayList<>();
        for (Entity entity : entities) {
            if (!entity.shouldBeSaved()) {
                continue; // drops one-player vehicles
            }
            TagValueOutput out = DiscardingTagOutput.create(registries);
            if (saveDroppingUnsavableLeashes(entity, out)) {
                CompoundTag entityTag = out.buildResult();
                applyMobPersistence(entityTag, entity, forceMobPersistence);
                entityTags.add(entityTag);
            }
        }
        return encodeChunk(entityTags, pos);
    }

    @Override
    public @Nullable CompoundTag captureRootVehicle(Entity vehicle, RegistryAccess registries,
            boolean forceMobPersistence) {
        // Vanilla saveParentVehicle serializes the root vehicle via root.save, bypassing shouldBeSaved (a one-player
        // vehicle fails it). A ridden mount that finishes then gets dismounted in the downloaded world can despawn like
        // any other captured mob, so it gets the same server-side PersistenceRequired restoration the standalone entity
        // path applies via applyMobPersistence (a named mob, a loot-equipped mob, and every mob under
        // forceMobPersistence).
        TagValueOutput out = DiscardingTagOutput.create(registries);
        if (!saveDroppingUnsavableLeashes(vehicle, out)) {
            return null;
        }
        CompoundTag tag = out.buildResult();
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag;
    }

    /**
     * A leash with neither a resolved holder nor a delayed attachment is what vanilla's {@link Leashable.LeashData}
     * codec {@code requireNonNulls} on, so {@link Entity#save} throws on it; and because save recurses into passengers,
     * a passenger's unsavable leash aborts the whole vehicle group, dropping a chested mob's container with it. An item
     * component the disk codec rejects costs either the entity or the whole field being written, so
     * {@link #sanitizeStacks} repairs the stack up front rather than recovering from either outcome. Each detached
     * leash, swapped stack, and sit order is restored before returning so the live entities are unchanged.
     */
    private static boolean saveDroppingUnsavableLeashes(Entity entity, TagValueOutput out) {
        List<DetachedLeash> detached = detachIfUnsavable(entity, null);
        List<Runnable> restores = sanitizeStacks(entity, null);
        restores = orderToSitFromPose(entity, restores);
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                detached = detachIfUnsavable(passenger, detached);
                restores = sanitizeStacks(passenger, restores);
                restores = orderToSitFromPose(passenger, restores);
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
            if (restores != null) {
                for (Runnable restore : restores) {
                    restore.run();
                }
            }
        }
    }

    /**
     * Restore the server-authoritative {@code Sitting} the client never receives: {@code TamableAnimal.orderedToSit} is
     * private server state, while the sit pose it drives is synced. Saved as {@code false}, a pet stands up on load and
     * walks or teleports to an owner in the world.
     */
    private static @Nullable List<Runnable> orderToSitFromPose(Entity entity, @Nullable List<Runnable> restores) {
        if (entity instanceof TamableAnimal tamable) {
            boolean ordered = tamable.isOrderedToSit();
            boolean posed = tamable.isInSittingPose();
            if (ordered != posed) {
                tamable.setOrderedToSit(posed);
                restores = addRestore(restores, () -> tamable.setOrderedToSit(ordered));
            }
        }
        return restores;
    }

    /**
     * Keep {@code ops} lazy: {@code entity.registryAccess()} reaches through the entity's level, so hoisting the build
     * into the wrapper would demand a level of entities this method otherwise never touches.
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
     * set it that the capture can reconstruct: the {@code NameTagItem} sets it on any {@link Mob} it renames, and
     * {@code Mob.setItemSlotAndDropWhenKilled} sets it when a mob equips a picked-up item. A named mob and a mob whose
     * equipment proves such a pickup (an item impossible for its natural spawn,
     * {@link NaturalEquipment#wasLootEquipped}) are both stamped unconditionally.
     */
    static void applyMobPersistence(CompoundTag entityTag, boolean isMob, boolean namedMob,
            boolean derivedPickup, boolean forceMobPersistence) {
        if (namedMob || derivedPickup || (isMob && forceMobPersistence)) {
            entityTag.putBoolean("PersistenceRequired", true);
        }
    }

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
            return null;
        }
        ListTag entities = new ListTag();
        for (CompoundTag entityTag : entityTags) {
            entities.add(entityTag);
        }
        CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.put("Entities", entities);
        tag.store("Position", ChunkPos.CODEC, pos);
        return tag;
    }
}
