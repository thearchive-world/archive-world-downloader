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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.EntitySink;

/**
 * 1.20.4 entity sink: a client-safe lift of {@code EntityStorage.storeEntities}'s write branch.
 *
 * <p>Three members (see {@link EntitySink}): {@link #encodeChunk(List, ChunkPos, RegistryAccess, boolean)} serializes
 * the live client entities, {@link #encodeChunk(List, ChunkPos)} builds the entities-region envelope from
 * already-serialized tags (pure, so the headless round-trip guards it), plus
 * {@link #captureRootVehicle(Entity, RegistryAccess, boolean)}, a single-live gate-bypassing vehicle serialize.
 */
public final class EntitySinkImpl implements EntitySink {
    /** Carpet items in {@link DyeColor#getId()} order, vanilla shipping no inverse of its carpet-to-color map. */
    private static final List<Item> CARPETS_BY_DYE_ID = List.of(
            Items.WHITE_CARPET, Items.ORANGE_CARPET, Items.MAGENTA_CARPET, Items.LIGHT_BLUE_CARPET,
            Items.YELLOW_CARPET, Items.LIME_CARPET, Items.PINK_CARPET, Items.GRAY_CARPET,
            Items.LIGHT_GRAY_CARPET, Items.CYAN_CARPET, Items.PURPLE_CARPET, Items.BLUE_CARPET,
            Items.BROWN_CARPET, Items.GREEN_CARPET, Items.RED_CARPET, Items.BLACK_CARPET);

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
                applySaddleItem(entityTag, entity);
                applyMountArmor(entityTag, entity);
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
        applySaddleItem(tag, vehicle);
        applyMountArmor(tag, vehicle);
        applyMobPersistence(tag, vehicle, forceMobPersistence);
        return tag;
    }

    /**
     * Write the saddle a horse-family mount is wearing, which vanilla's own writer cannot see. Below the 1.21.5
     * equipment-slot cut the saddle is a real stack in the mount's inventory slot 0, and the server never sends that
     * container to the client, so vanilla finds slot 0 empty and emits no {@code SaddleItem}: a saddled mount archives
     * unsaddled. What does arrive is the saddled bit of the synced entity-data flags, for every saddled mount and with
     * no interaction, so the capture writes a plain saddle from it and vanilla's load path takes it from there, keeping
     * the stack only if it is a saddle and re-deriving the flag itself.
     *
     * <p>Re-derived from synced state on every capture, so a partial re-flush cannot drop it and {@code EntityMerge}
     * needs no carry-forward key for it. A renamed or component-bearing saddle saves as a plain one, the accepted
     * residual: outside an open mount menu the client holds no source for a saddle's components. An existing key is
     * left alone rather than overwritten, so a stack that did reach slot 0 keeps whatever it carries.
     */
    static void applySaddleItem(CompoundTag entityTag, boolean saddled) {
        if (saddled && !entityTag.contains("SaddleItem", Tag.TAG_COMPOUND)) {
            entityTag.put("SaddleItem", new ItemStack(Items.SADDLE).save(new CompoundTag()));
        }
    }

    /**
     * Stamp every saddled horse-family mount in a saved entity group, matched by UUID rather than by position.
     *
     * <p>The nested half is the reason this is not a one-liner on the root tag. The entity save has already recursed
     * into {@code "Passengers"} by the time it returns, so a mount that is itself a passenger reaches disk only as a
     * nested compound, and a mount picked up by a plain minecart is exactly that. Position cannot be trusted to find
     * it, because vanilla drops any passenger whose own save is refused and the surviving list is then shorter than the
     * live one, so the walk keys on the {@code "UUID"} every entity tag carries.
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
        if (entity instanceof AbstractHorse horse && horse.isSaddled()) {
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
        ListTag passengers = entityTag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            stampSaddled(passengers.getCompound(i), saddled);
        }
    }

    /** The band's own entity-tag UUID read, the same one {@code EntityMerge} uses. */
    private static @Nullable UUID readUuid(CompoundTag tag) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("UUID")).result().orElse(null);
    }

    /**
     * Write what a mount wears in its own inventory slot 1, which vanilla's own writer cannot see. Below the 1.20.5
     * body-armor cut a horse's armor and a llama's carpet are both real stacks in that slot, a container the server
     * never sends to the client, so vanilla finds the slot empty and emits neither {@code ArmorItem} nor
     * {@code DecorItem}, the two keys it saves that one slot under. The horse needs the write even though its armor
     * already reaches the archive as synced chest equipment, because vanilla's load side re-derives that mirror from
     * the empty slot 1 and wipes it.
     *
     * <p>A horse's armor is written whole, the client holding the real stack; a llama's carpet reconstructs from the
     * synced dye color alone, so it is written plain. An existing key is left alone rather than overwritten, and the
     * write is re-derived from synced state on every capture, so {@code EntityMerge} needs no carry-forward key.
     */
    static void applyMountArmor(CompoundTag entityTag, CompoundTag worn) {
        for (String key : worn.getAllKeys()) {
            if (!entityTag.contains(key, Tag.TAG_COMPOUND)) {
                entityTag.put(key, worn.getCompound(key));
            }
        }
    }

    /**
     * Stamp each horse's armor and each llama's carpet in a saved group, by UUID rather than by position: the descent
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

    /** The single-key patch a live mount's saved tag owes. */
    private static @Nullable CompoundTag wornMountArmor(Entity entity) {
        String key;
        ItemStack stack;
        if (entity instanceof Horse horse) {
            key = "ArmorItem";
            stack = horse.getArmor();
        } else if (entity instanceof Llama llama) {
            DyeColor color = llama.getSwag();
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

    private static void stampMountArmor(CompoundTag entityTag, Map<UUID, CompoundTag> worn) {
        UUID uuid = readUuid(entityTag);
        CompoundTag item = uuid != null ? worn.get(uuid) : null;
        if (item != null) {
            applyMountArmor(entityTag, item);
        }
        ListTag passengers = entityTag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) {
            stampMountArmor(passengers.getCompound(i), worn);
        }
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
