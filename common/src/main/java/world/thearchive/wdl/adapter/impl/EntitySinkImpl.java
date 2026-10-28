// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.passive.class_2976;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

public final class EntitySinkImpl implements EntitySink {
    /** Carpet blocks in {@link DyeColor#getId()} order, vanilla shipping no inverse of its carpet-to-color map. */
    private static final List<Block> CARPETS_BY_DYE_ID = ImmutableList.of(
            Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.MAGENTA_CARPET, Blocks.LIGHT_BLUE_CARPET,
            Blocks.YELLOW_CARPET, Blocks.LIME_CARPET, Blocks.PINK_CARPET, Blocks.GRAY_CARPET,
            Blocks.LIGHT_GRAY_CARPET, Blocks.CYAN_CARPET, Blocks.PURPLE_CARPET, Blocks.BLUE_CARPET,
            Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.RED_CARPET, Blocks.BLACK_CARPET);

    private static boolean shouldSaveEntity(Entity entity) {
        return !entity.removed && !entity.isPassenger()
                && (!entity.isVehicle() || !entity.hasOnePlayerPassenger());
    }

    /**
     * Below 1.15 vanilla {@code ItemStack.save} puts the live stack's own {@code tag} compound into its output, so each
     * entity tag is detached before it is handed on: the caller owns it, and the client keeps nothing the map-id remap,
     * the coordinate scrub or the save writer could reach.
     */
    @Override
    public @Nullable CompoundTag encodeChunk(List<Entity> entities, ChunkPos pos, boolean forceMobPersistence) {
        List<CompoundTag> entityTags = new ArrayList<>();
        for (Entity entity : entities) {
            if (!shouldSaveEntity(entity)) {
                continue;
            }
            CompoundTag entityTag = new CompoundTag();
            if (entity.save(entityTag)) {
                applySaddleItem(entityTag, entity);
                applyMountArmor(entityTag, entity);
                applyMobPersistence(entityTag, entity, forceMobPersistence);
                entityTags.add(entityTag.copy());
            }
        }
        return encodeChunk(entityTags, pos);
    }

    @Override
    public @Nullable CompoundTag captureRootVehicle(Entity vehicle, boolean forceMobPersistence) {
        CompoundTag tag = new CompoundTag();
        if (!vehicle.save(tag)) {
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
    static void applySaddleItem(CompoundTag entityTag, boolean saddled) {
        if (saddled && !entityTag.contains("SaddleItem", 10)) {
            entityTag.put("SaddleItem", new ItemStack(Items.SADDLE).save(new CompoundTag()));
        }
    }

    /**
     * The entity save has already recursed into {@code "Passengers"} by the time it returns, so a mount that is itself
     * a passenger reaches disk only as a nested compound, and a mount picked up by a plain minecart is exactly that.
     */
    static void applySaddleItem(CompoundTag entityTag, Entity entity) {
        Set<UUID> saddled = collectSaddled(entity, null);
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                saddled = collectSaddled(passenger, saddled);
            }
        }
        if (saddled != null) {
            stampSaddled(entityTag, saddled);
        }
    }

    private static @Nullable Set<UUID> collectSaddled(Entity entity, @Nullable Set<UUID> saddled) {
        if (entity instanceof AbstractHorse && ((AbstractHorse) entity).isSaddled()) {
            Set<UUID> set = saddled != null ? saddled : new HashSet<>();
            set.add(entity.getUUID());
            return set;
        }
        return saddled;
    }

    private static void stampSaddled(CompoundTag entityTag, Set<UUID> saddled) {
        UUID uuid = readUuid(entityTag);
        if (uuid != null && saddled.contains(uuid)) {
            applySaddleItem(entityTag, true);
        }
        ListTag passengers = entityTag.getList("Passengers", 10);
        for (int i = 0; i < passengers.size(); i++) {
            stampSaddled(passengers.getCompound(i), saddled);
        }
    }

    private static @Nullable UUID readUuid(CompoundTag tag) {
        return tag.hasUUID("UUID") ? tag.getUUID("UUID") : null;
    }

    /**
     * Write what a mount wears in its own inventory slot 1, which vanilla's own writer cannot see. Below the 1.20.5
     * body-armor cut a horse's armor and a llama's carpet are both real stacks in that slot, a container the client
     * never fills, so vanilla finds the slot empty and emits neither {@code ArmorItem} nor {@code DecorItem}, the two
     * keys it saves that one slot under.
     */
    static void applyMountArmor(CompoundTag entityTag, CompoundTag worn) {
        for (String key : worn.getAllKeys()) {
            if (!entityTag.contains(key, 10)) {
                entityTag.put(key, worn.getCompound(key));
            }
        }
    }

    /**
     * Stamp each horse's armor and each llama's carpet in a saved group: the descent
     * {@link #applySaddleItem(CompoundTag, Entity)} makes, and for the same reason.
     */
    static void applyMountArmor(CompoundTag entityTag, Entity entity) {
        Map<UUID, CompoundTag> worn = collectMountArmor(entity, null);
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                worn = collectMountArmor(passenger, worn);
            }
        }
        if (worn != null) {
            stampMountArmor(entityTag, worn);
        }
    }

    private static @Nullable Map<UUID, CompoundTag> collectMountArmor(Entity entity,
            @Nullable Map<UUID, CompoundTag> worn) {
        CompoundTag item = wornMountArmor(entity);
        if (item == null) {
            return worn;
        }
        Map<UUID, CompoundTag> collected = worn != null ? worn : new HashMap<>();
        collected.put(entity.getUUID(), item);
        return collected;
    }

    private static @Nullable CompoundTag wornMountArmor(Entity entity) {
        String key;
        ItemStack stack;
        if (entity instanceof Horse) {
            key = "ArmorItem";
            stack = horseArmor((Horse) entity);
        } else if (entity instanceof Llama) {
            DyeColor color = ((Llama) entity).getSwag();
            if (color == null) {
                return null;
            }
            key = "DecorItem";
            stack = new ItemStack(CARPETS_BY_DYE_ID.get(color.getId()));
        } else {
            return null;
        }
        if (stack.isEmpty()) {
            return null;
        }
        CompoundTag worn = new CompoundTag();
        worn.put(key, stack.save(new CompoundTag()));
        return worn;
    }

    /**
     * The tier accessor indexes the enum's own values array with no bounds check, so a value outside the vanilla range
     * throws rather than reading as NONE; catching it costs one horse's armor.
     */
    private static ItemStack horseArmor(Horse horse) {
        class_2976 tier;
        try {
            tier = horse.getArmor();
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

    private static void stampMountArmor(CompoundTag entityTag, Map<UUID, CompoundTag> worn) {
        UUID uuid = readUuid(entityTag);
        CompoundTag item = uuid != null ? worn.get(uuid) : null;
        if (item != null) {
            applyMountArmor(entityTag, item);
        }
        ListTag passengers = entityTag.getList("Passengers", 10);
        for (int i = 0; i < passengers.size(); i++) {
            stampMountArmor(passengers.getCompound(i), worn);
        }
    }

    /**
     * Restore the server-authoritative {@code PersistenceRequired} the client never receives. Two vanilla mechanisms
     * set it that the capture can reconstruct: the name-tag item sets it on any {@link Mob} it renames, and
     * {@code Mob.pickUpItem} sets it when a mob equips a picked-up item. A named mob and a mob whose equipment proves
     * such a pickup (an item impossible for its natural spawn, {@link NaturalEquipment#wasLootEquipped}) are both
     * stamped unconditionally.
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
        if (entity instanceof Mob) {
            Mob mob = (Mob) entity;
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
        CompoundTag tag = new CompoundTag();
        tag.put("Entities", entities);
        return tag;
    }
}
