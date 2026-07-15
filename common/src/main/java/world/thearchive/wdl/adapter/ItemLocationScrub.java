// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Reusable, band-agnostic privacy scrub for item-borne location data: blanks the lodestone-compass target and the
 * flower position of each bee inside a beehive item on every item it reaches, recursing into shulker boxes (the
 * {@code minecraft:container} component) and bundles (the {@code minecraft:bundle_contents} component) over the shared
 * {@link ItemTreeWalk}. Blanking removes only the coordinate key: the {@code "target"} subkey of the
 * {@code minecraft:lodestone_tracker} component (leaving the {@code tracked} flag, so the compass stays a valid
 * lodestone compass that points nowhere, exactly the state vanilla produces when a tracked lodestone is destroyed) and
 * the {@code "flower_pos"} subkey of each occupant's {@code entity_data} in the {@code minecraft:bees} component
 * (leaving the occupant a valid bee).
 *
 * <p>Operates only on already-serialized NBT (our own captured copy), never on a live {@code ItemStack}, so it cannot
 * corrupt the player's session. Uses only the post-1.20.5-stable component NBT shape ({@code {id, count, components}})
 * and the band-stable {@code get}/{@code instanceof}/{@code remove} NBT ops (the {@link ContainerMerge} discipline), so
 * it is byte-identical across the era bands. Kept callable at any item-bearing persist surface (the inventory list, the
 * ender-items list, a drained container holder) by taking the holder plus the list key rather than hard-coding the
 * player tag.
 *
 * <p>Reaches items three ways: an item-list holder via {@link #scrub(CompoundTag, String)} (the inventory, the ender
 * items, a drained container), a chunk-path block entity via {@link #scrubBlockEntity(CompoundTag)} (a decorated pot, a
 * shelf), and a serialized entity via {@link #scrubEntity(CompoundTag)} (an item frame, an item display, mob equipment,
 * an allay, a dropped item, and their passengers).
 *
 * <p>Scope, stated so the toggle does not over-promise: the scrub blanks the lodestone target and the beehive bee
 * flower positions only. {@code minecraft:custom_data} and {@code minecraft:map_decorations} are the two named
 * residuals, and both do reach a client when a server puts them on an item (a component registered without a dedicated
 * network codec still syncs through one derived from its persistent codec). They stay unscrubbed deliberately: vanilla
 * authors {@code map_decorations} only on explorer maps pointing at seed-derived structures, {@code custom_data} is
 * opaque server NBT whose coordinate leak is speculative, and blanking either whole component would corrupt legitimate
 * items, unlike the single-subkey removals above.
 */
final class ItemLocationScrub {
    private static final String LODESTONE_TRACKER = "minecraft:lodestone_tracker";
    private static final String TARGET = "target";
    private static final String BEES = "minecraft:bees";
    private static final String ENTITY_DATA = "entity_data";
    private static final String FLOWER_POS = "flower_pos";
    private static final String EQUIPMENT = "equipment";
    private static final String PASSENGERS = "Passengers";

    private ItemLocationScrub() {}

    /**
     * Blank every item-borne coordinate on every item in {@code holder}'s {@code listKey} list (each list element is an
     * item-NBT compound, with or without a leading {@code "Slot"}), recursing into nested containers and bundles. A
     * missing or non-list {@code listKey} is a no-op. The holder is mutated in place.
     */
    public static void scrub(CompoundTag holder, String listKey) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, ItemLocationScrub::scrubComponents);
        }
    }

    /**
     * Blank every item-borne coordinate on every item held by {@code blockEntity}, wherever the block entity stores it
     * (a decorated pot's {@code item}, a campfire's {@code Items}, and so on), recursing into nested containers and
     * bundles. Walks the block entity's direct children through {@link ItemTreeWalk}, which acts only on a real item
     * {@code components} map, so non-item children (the block entity's own {@code components} metadata, its
     * coordinates, its type id) are a no-op. Works for every block-entity type without a per-type key list.
     */
    public static void scrubBlockEntity(CompoundTag blockEntity) {
        for (String key : blockEntity.keySet()) {
            Tag value = blockEntity.get(key);
            if (value instanceof ListTag list) {
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubComponents);
            } else if (value instanceof CompoundTag compound) {
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubComponents);
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
        for (String key : entity.keySet()) {
            Tag value = entity.get(key);
            if (value instanceof ListTag list) {
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubComponents);
            } else if (value instanceof CompoundTag compound) {
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubComponents);
            }
        }
        if (entity.get(EQUIPMENT) instanceof CompoundTag equipment) {
            for (String slot : equipment.keySet()) {
                if (equipment.get(slot) instanceof CompoundTag item) {
                    ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubComponents);
                }
            }
        }
        if (entity.get(PASSENGERS) instanceof ListTag passengers) {
            for (int i = 0; i < passengers.size(); i++) {
                if (passengers.get(i) instanceof CompoundTag passenger) {
                    scrubEntity(passenger);
                }
            }
        }
    }

    /** Blank the lodestone target and the bee flower positions on {@code components}. */
    private static void scrubComponents(CompoundTag components) {
        if (components.get(LODESTONE_TRACKER) instanceof CompoundTag tracker) {
            tracker.remove(TARGET);
        }
        if (components.get(BEES) instanceof ListTag bees) {
            for (int i = 0; i < bees.size(); i++) {
                if (bees.get(i) instanceof CompoundTag occupant
                        && occupant.get(ENTITY_DATA) instanceof CompoundTag entityData) {
                    entityData.remove(FLOWER_POS);
                }
            }
        }
    }
}
