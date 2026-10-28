// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * On a beehive item the scrub removes each occupant's {@code BlockEntityTag.Bees[].EntityData.FlowerPos} (leaving each
 * a valid bee).
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

    public static void scrub(CompoundTag holder, String listKey) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
        }
    }

    public static void scrubBlockEntity(CompoundTag blockEntity) {
        for (String key : blockEntity.getAllKeys()) {
            Tag value = blockEntity.get(key);
            if (value instanceof ListTag list) {
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof CompoundTag compound) {
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
    }

    /**
     * Two entity-specific shapes the generic walk cannot reach are handled explicitly: {@code equipment}, where the
     * version writes it, is a compound of slot to item, so each value is walked; {@code Passengers} is a list of nested
     * entities, so each is recursed.
     */
    public static void scrubEntity(CompoundTag entity) {
        for (String key : entity.getAllKeys()) {
            Tag value = entity.get(key);
            if (value instanceof ListTag list) {
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof CompoundTag compound) {
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
        if (entity.get(EQUIPMENT) instanceof CompoundTag equipment) {
            for (String slot : equipment.getAllKeys()) {
                if (equipment.get(slot) instanceof CompoundTag item) {
                    ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
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

    public static void scrubItem(CompoundTag item) {
        ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
    }

    private static void scrubItemTag(CompoundTag tag) {
        tag.remove(LODESTONE_POS);
        tag.remove(LODESTONE_DIMENSION);
        if (tag.get(BLOCK_ENTITY_TAG) instanceof CompoundTag blockEntityTag) {
            blockEntityTag.remove(FLOWER_POS);
            if (blockEntityTag.get(BEES) instanceof ListTag bees) {
                for (int i = 0; i < bees.size(); i++) {
                    if (bees.get(i) instanceof CompoundTag occupant
                            && occupant.get(ENTITY_DATA) instanceof CompoundTag entityData) {
                        entityData.remove(FLOWER_POS);
                    }
                }
            }
        }
    }
}
