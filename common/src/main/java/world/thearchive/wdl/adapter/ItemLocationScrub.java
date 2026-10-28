// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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

    public static void scrub(NBTTagCompound holder, String listKey) {
        if (holder.getTag(listKey) instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) holder.getTag(listKey);
            ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
        }
    }

    public static void scrubBlockEntity(NBTTagCompound blockEntity) {
        for (String key : blockEntity.getKeySet()) {
            NBTBase value = blockEntity.getTag(key);
            if (value instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) value;
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof NBTTagCompound) {
                NBTTagCompound compound = (NBTTagCompound) value;
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
    }

    public static void scrubEntity(NBTTagCompound entity) {
        for (String key : entity.getKeySet()) {
            NBTBase value = entity.getTag(key);
            if (value instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) value;
                ItemTreeWalk.walkList(list, ItemLocationScrub::scrubItemTag);
            } else if (value instanceof NBTTagCompound) {
                NBTTagCompound compound = (NBTTagCompound) value;
                ItemTreeWalk.walkItem(compound, ItemLocationScrub::scrubItemTag);
            }
        }
        if (entity.getTag(EQUIPMENT) instanceof NBTTagCompound) {
            NBTTagCompound equipment = (NBTTagCompound) entity.getTag(EQUIPMENT);
            for (String slot : equipment.getKeySet()) {
                if (equipment.getTag(slot) instanceof NBTTagCompound) {
                    NBTTagCompound item = (NBTTagCompound) equipment.getTag(slot);
                    ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
                }
            }
        }
        if (entity.getTag(PASSENGERS) instanceof NBTTagList) {
            NBTTagList passengers = (NBTTagList) entity.getTag(PASSENGERS);
            for (NBTBase element : NbtElements.of(passengers)) {
                if (element instanceof NBTTagCompound) {
                    NBTTagCompound passenger = (NBTTagCompound) element;
                    scrubEntity(passenger);
                }
            }
        }
    }

    public static void scrubItem(NBTTagCompound item) {
        ItemTreeWalk.walkItem(item, ItemLocationScrub::scrubItemTag);
    }

    private static void scrubItemTag(NBTTagCompound tag) {
        tag.removeTag(LODESTONE_POS);
        tag.removeTag(LODESTONE_DIMENSION);
        if (tag.getTag(BLOCK_ENTITY_TAG) instanceof NBTTagCompound) {
            NBTTagCompound blockEntityTag = (NBTTagCompound) tag.getTag(BLOCK_ENTITY_TAG);
            blockEntityTag.removeTag(FLOWER_POS);
            if (blockEntityTag.getTag(BEES) instanceof NBTTagList) {
                NBTTagList bees = (NBTTagList) blockEntityTag.getTag(BEES);
                for (NBTBase element : NbtElements.of(bees)) {
                    NBTTagCompound occupant = element instanceof NBTTagCompound ? (NBTTagCompound) element : null;
                    if (occupant != null && occupant.getTag(ENTITY_DATA) instanceof NBTTagCompound) {
                        NBTTagCompound entityData = (NBTTagCompound) occupant.getTag(ENTITY_DATA);
                        entityData.removeTag(FLOWER_POS);
                    }
                }
            }
        }
    }
}
