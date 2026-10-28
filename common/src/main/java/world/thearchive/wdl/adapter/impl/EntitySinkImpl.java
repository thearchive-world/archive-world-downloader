// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.entity.passive.HorseArmorType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

public final class EntitySinkImpl implements EntitySink {
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
        List<NBTTagCompound> entityTags = new ArrayList<>();
        for (Entity entity : entities) {
            if (!shouldSaveEntity(entity)) {
                continue; // drops one-player vehicles
            }
            NBTTagCompound entityTag = new NBTTagCompound();
            if (entity.writeToNBTOptional(entityTag)) {
                applySaddleItem(entityTag, entity);
                applyMountArmor(entityTag, entity);
                applyMobPersistence(entityTag, entity, forceMobPersistence);
                entityTags.add(entityTag.copy());
            }
        }
        return encodeChunk(entityTags, pos);
    }

    @Override
    public @Nullable NBTTagCompound captureRootVehicle(Entity vehicle, boolean forceMobPersistence) {
        // Vanilla saves a ridden root vehicle via writeToNBTOptional, bypassing the standalone gate (a one-player
        // vehicle fails it). A ridden mount that finishes then gets dismounted in the downloaded world can despawn like
        // any other captured mob, so it gets the same server-side PersistenceRequired restoration the standalone entity
        // path applies via applyMobPersistence (a named mob, a loot-equipped mob, and every mob under
        // forceMobPersistence).
        NBTTagCompound tag = new NBTTagCompound();
        if (!vehicle.writeToNBTOptional(tag)) {
            return null;
        }
        applySaddleItem(tag, vehicle);
        applyMountArmor(tag, vehicle);
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag.copy();
    }

    /**
     * Below the 1.21.5 equipment-slot cut the saddle is a real stack in the mount's inventory slot 0, and the client
     * never fills that slot, so vanilla finds slot 0 empty and emits no {@code SaddleItem}: a saddled mount archives
     * unsaddled.
     *
     * <p>An existing key is left alone rather than overwritten, so a stack that did reach slot 0 keeps whatever it
     * carries.
     */
    static void applySaddleItem(NBTTagCompound entityTag, boolean saddled) {
        if (saddled && !entityTag.hasKey("SaddleItem", 10)) {
            entityTag.setTag("SaddleItem", new ItemStack(Items.SADDLE).writeToNBT(new NBTTagCompound()));
        }
    }

    /**
     * The entity save has already recursed into {@code "Passengers"} by the time it returns, so a mount that is itself
     * a passenger reaches disk only as a nested compound, and a mount picked up by a plain minecart is exactly that.
     */
    static void applySaddleItem(NBTTagCompound entityTag, Entity entity) {
        Set<UUID> saddled = collectSaddled(entity, null);
        if (entity.isBeingRidden()) {
            for (Entity passenger : entity.getRecursivePassengers()) {
                saddled = collectSaddled(passenger, saddled);
            }
        }
        if (saddled != null) {
            stampSaddled(entityTag, saddled);
        }
    }

    private static @Nullable Set<UUID> collectSaddled(Entity entity, @Nullable Set<UUID> saddled) {
        if (entity instanceof AbstractHorse && ((AbstractHorse) entity).isHorseSaddled()) {
            Set<UUID> set = saddled != null ? saddled : new HashSet<>();
            set.add(entity.getUniqueID());
            return set;
        }
        return saddled;
    }

    private static void stampSaddled(NBTTagCompound entityTag, Set<UUID> saddled) {
        UUID uuid = readUuid(entityTag);
        if (uuid != null && saddled.contains(uuid)) {
            applySaddleItem(entityTag, true);
        }
        NBTTagList passengers = entityTag.getTagList("Passengers", 10);
        for (int i = 0; i < passengers.tagCount(); i++) {
            stampSaddled(passengers.getCompoundTagAt(i), saddled);
        }
    }

    private static @Nullable UUID readUuid(NBTTagCompound tag) {
        return tag.hasUniqueId("UUID") ? tag.getUniqueId("UUID") : null;
    }

    /**
     * Write what a mount wears in its own inventory slot 1, which vanilla's own writer cannot see. Below the 1.20.5
     * body-armor cut a horse's armor and a llama's carpet are both real stacks in that slot, a container the client
     * never fills, so vanilla finds the slot empty and emits neither {@code ArmorItem} nor {@code DecorItem}, the two
     * keys it saves that one slot under.
     */
    static void applyMountArmor(NBTTagCompound entityTag, NBTTagCompound worn) {
        for (String key : worn.getKeySet()) {
            if (!entityTag.hasKey(key, 10)) {
                entityTag.setTag(key, worn.getCompoundTag(key));
            }
        }
    }

    /**
     * Stamp each horse's armor and each llama's carpet in a saved group: the descent
     * {@link #applySaddleItem(NBTTagCompound, Entity)} makes, and for the same reason.
     */
    static void applyMountArmor(NBTTagCompound entityTag, Entity entity) {
        Map<UUID, NBTTagCompound> worn = collectMountArmor(entity, null);
        if (entity.isBeingRidden()) {
            for (Entity passenger : entity.getRecursivePassengers()) {
                worn = collectMountArmor(passenger, worn);
            }
        }
        if (worn != null) {
            stampMountArmor(entityTag, worn);
        }
    }

    private static @Nullable Map<UUID, NBTTagCompound> collectMountArmor(Entity entity,
            @Nullable Map<UUID, NBTTagCompound> worn) {
        NBTTagCompound item = wornMountArmor(entity);
        if (item == null) {
            return worn;
        }
        Map<UUID, NBTTagCompound> collected = worn != null ? worn : new HashMap<>();
        collected.put(entity.getUniqueID(), item);
        return collected;
    }

    private static @Nullable NBTTagCompound wornMountArmor(Entity entity) {
        String key;
        ItemStack stack;
        if (entity instanceof EntityHorse) {
            key = "ArmorItem";
            stack = horseArmor((EntityHorse) entity);
        } else if (entity instanceof EntityLlama) {
            EnumDyeColor color = ((EntityLlama) entity).getColor();
            if (color == null) {
                return null;
            }
            key = "DecorItem";
            stack = new ItemStack(Blocks.CARPET, 1, color.getMetadata());
        } else {
            return null;
        }
        if (stack.isEmpty()) {
            return null;
        }
        NBTTagCompound worn = new NBTTagCompound();
        worn.setTag(key, stack.writeToNBT(new NBTTagCompound()));
        return worn;
    }

    /**
     * The tier lookup indexes the enum's own values array with no bounds check, so a value outside the vanilla range
     * throws rather than reading as NONE; catching it costs one horse's armor.
     */
    private static ItemStack horseArmor(EntityHorse horse) {
        HorseArmorType tier;
        try {
            tier = horse.getHorseArmorType();
        } catch (ArrayIndexOutOfBoundsException e) {
            return ItemStack.EMPTY;
        }
        switch (tier) {
            case IRON:
                return new ItemStack(Items.IRON_HORSE_ARMOR);
            case GOLD:
                return new ItemStack(Items.GOLDEN_HORSE_ARMOR);
            case DIAMOND:
                return new ItemStack(Items.DIAMOND_HORSE_ARMOR);
            default:
                return ItemStack.EMPTY;
        }
    }

    private static void stampMountArmor(NBTTagCompound entityTag, Map<UUID, NBTTagCompound> worn) {
        UUID uuid = readUuid(entityTag);
        NBTTagCompound item = uuid != null ? worn.get(uuid) : null;
        if (item != null) {
            applyMountArmor(entityTag, item);
        }
        NBTTagList passengers = entityTag.getTagList("Passengers", 10);
        for (int i = 0; i < passengers.tagCount(); i++) {
            stampMountArmor(passengers.getCompoundTagAt(i), worn);
        }
    }

    /**
     * Restore the server-authoritative {@code PersistenceRequired} the client never receives. Two vanilla mechanisms
     * set it that the capture can reconstruct: the name-tag item sets it on any {@link EntityLiving} it renames, and
     * {@code EntityLiving.updateEquipmentIfNeeded} sets it when a mob equips a picked-up item. A named mob and a mob
     * whose equipment proves such a pickup (an item impossible for its natural spawn,
     * {@link NaturalEquipment#wasLootEquipped}) are both stamped unconditionally.
     */
    static void applyMobPersistence(NBTTagCompound entityTag, boolean isMob, boolean namedMob,
            boolean derivedPickup, boolean forceMobPersistence) {
        if (namedMob || derivedPickup || (isMob && forceMobPersistence)) {
            entityTag.setBoolean("PersistenceRequired", true);
        }
    }

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
