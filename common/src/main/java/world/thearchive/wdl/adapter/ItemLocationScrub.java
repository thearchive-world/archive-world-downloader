// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

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
        if (holder.get(listKey) instanceof ListTag) {
            ListTag list = (ListTag) holder.get(listKey);
            ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
        }
    }

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

    public static void scrubItem(CompoundTag item) {
        ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
    }

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
