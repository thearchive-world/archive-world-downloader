// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Reusable privacy scrub for item-borne location data: blanks the lodestone-compass target and the flower position
 * carried by a silk-touched beehive item on every item it reaches, recursing into shulker boxes (the item's
 * {@code BlockEntityTag.Items}) and bundles (the item's {@code Items}) over the shared {@link ItemTreeWalk}. Below the
 * 1.20.5 component update an item's data lives in its {@code tag} compound, so the scrub removes the coordinate keys
 * there: the lodestone target ({@code LodestonePos} plus {@code LodestoneDimension}, leaving {@code LodestoneTracked})
 * and, on a beehive item, the hive's own {@code BlockEntityTag.FlowerPos} and each occupant's
 * {@code BlockEntityTag.Bees[].EntityData.FlowerPos} (leaving each a valid bee). The pre-component item copies the
 * hive's whole block-entity NBT, so it carries the hive's own top-level flower position too, which the component era
 * drops; both are stripped here.
 *
 * <p>Operates only on already-serialized NBT (our own captured copy), never on a live {@code ItemStack}, so it cannot
 * corrupt the player's session. Below the 1.20.5 update it uses the {@code {id, Count, tag}} item shape and the
 * band-stable {@code get}/{@code instanceof}/{@code remove} NBT ops, so this band re-authors the pre-component key
 * paths while the walk and merge discipline are shared.
 *
 * <p>Reaches items three ways: an item-list holder via {@link #scrub(CompoundTag, String)} (the inventory, the ender
 * items, a drained container), a chunk-path block entity via {@link #scrubBlockEntity(CompoundTag)} (a decorated pot, a
 * shelf), and a serialized entity via {@link #scrubEntity(CompoundTag)} (an item frame, an item display, mob equipment,
 * an allay, a dropped item, and their passengers).
 *
 * <p>Scope, stated so the toggle does not over-promise: the scrub blanks the lodestone target and the beehive flower
 * positions only. Opaque server NBT whose coordinate leak is speculative is left alone, since blanking a whole unknown
 * subtree would corrupt legitimate items, unlike the single-key removals above.
 */
final class ItemLocationScrub {
    private static final String LODESTONE_POS = "LodestonePos";
    private static final String LODESTONE_DIMENSION = "LodestoneDimension";
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String BEES = "Bees";
    private static final String ENTITY_DATA = "EntityData";
    private static final String FLOWER_POS = "FlowerPos";
    private static final String EQUIPMENT = "equipment";
    private static final String PASSENGERS = "Passengers";

    private ItemLocationScrub() {}

    /**
     * Blank every item-borne coordinate on every item in {@code holder}'s {@code listKey} list (each list element is an
     * item-NBT compound, with or without a leading {@code "Slot"}), recursing into nested containers and bundles. A
     * missing or non-list {@code listKey} is a no-op. The holder is mutated in place.
     */
    public static void scrub(CompoundTag holder, String listKey) {
        if (holder.get(listKey) instanceof ListTag) {
            ListTag list = (ListTag) holder.get(listKey);
            ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
        }
    }

    /**
     * Blank every item-borne coordinate on every item held by {@code blockEntity}, wherever the block entity stores it
     * (a decorated pot's {@code item}, a campfire's {@code Items}, and so on), recursing into nested containers and
     * bundles. Walks the block entity's direct children through {@link ItemTreeWalk}, which acts only on a real item's
     * {@code tag}, so non-item children (the block entity's own coordinates, its type id) are a no-op. Works for every
     * block-entity type without a per-type key list.
     */
    public static void scrubBlockEntity(CompoundTag blockEntity) {
        for (String key : blockEntity.getAllKeys()) {
            Tag value = blockEntity.get(key);
            if (value instanceof ListTag) {
                ListTag list = (ListTag) value;
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof CompoundTag) {
                CompoundTag compound = (CompoundTag) value;
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
    }

    /**
     * Blank every item-borne coordinate on {@code entity} and, recursively, on its passengers. Walks the direct
     * children name-agnostically (any direct child item compound, any item list), so it reaches the displayed item
     * under either {@code Item} or the lowercase {@code item}, and inventory lists, without hard-coding field names.
     * Two entity-specific shapes the generic walk cannot reach are handled explicitly: {@code equipment} is a compound
     * of slot to item, so each value is walked; {@code Passengers} is a list of nested entities, so each is recursed.
     * Works only on already-serialized entity NBT.
     */
    public static void scrubEntity(CompoundTag entity) {
        for (String key : entity.getAllKeys()) {
            Tag value = entity.get(key);
            if (value instanceof ListTag) {
                ListTag list = (ListTag) value;
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof CompoundTag) {
                CompoundTag compound = (CompoundTag) value;
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
        if (entity.get(EQUIPMENT) instanceof CompoundTag) {
            CompoundTag equipment = (CompoundTag) entity.get(EQUIPMENT);
            for (String slot : equipment.getAllKeys()) {
                if (equipment.get(slot) instanceof CompoundTag) {
                    CompoundTag item = (CompoundTag) equipment.get(slot);
                    ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
                }
            }
        }
        if (entity.get(PASSENGERS) instanceof ListTag) {
            ListTag passengers = (ListTag) entity.get(PASSENGERS);
            for (Tag element : passengers) {
                if (element instanceof CompoundTag) {
                    CompoundTag passenger = (CompoundTag) element;
                    scrubEntity(passenger);
                }
            }
        }
    }

    /**
     * Blank every item-borne coordinate on a single serialized item compound ({@code {id, Count, tag}}), recursing into
     * nested containers and bundles. The single-item entry, for an offer's {@code sell} item, which sits under no list
     * holder.
     */
    public static void scrubItem(CompoundTag item) {
        ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
    }

    /** Blank the lodestone target and the beehive flower positions on an item's {@code tag}. */
    private static void scrubItemTag(CompoundTag tag) {
        tag.remove(LODESTONE_POS);
        tag.remove(LODESTONE_DIMENSION);
        if (tag.get(BLOCK_ENTITY_TAG) instanceof CompoundTag) {
            CompoundTag blockEntityTag = (CompoundTag) tag.get(BLOCK_ENTITY_TAG);
            blockEntityTag.remove(FLOWER_POS);
            if (blockEntityTag.get(BEES) instanceof ListTag) {
                ListTag bees = (ListTag) blockEntityTag.get(BEES);
                for (Tag element : bees) {
                    CompoundTag occupant = element instanceof CompoundTag ? (CompoundTag) element : null;
                    if (occupant != null && occupant.get(ENTITY_DATA) instanceof CompoundTag) {
                        CompoundTag entityData = (CompoundTag) occupant.get(ENTITY_DATA);
                        entityData.remove(FLOWER_POS);
                    }
                }
            }
        }
    }
}
