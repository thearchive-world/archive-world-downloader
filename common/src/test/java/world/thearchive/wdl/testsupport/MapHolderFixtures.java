// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import world.thearchive.wdl.adapter.ContainerSink;

/**
 * Shared map-item-holder NBT fixtures for the map archive tests: a filled-map stack, a shulker nesting one, and the
 * captured {@code Items} holder they serialize into. Hoisted here so the id collector, remap, and archive tests share
 * one copy rather than each carrying its own.
 */
public final class MapHolderFixtures {
    private MapHolderFixtures() {}

    /**
     * A filled-map stack carrying {@code id} in its raw {@code "map"} tag (below 1.20.5, no {@code map_id} component).
     */
    public static ItemStack filledMap(int id) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.getOrCreateTag().putInt("map", id);
        return map;
    }

    /**
     * A shulker box whose {@code tag.BlockEntityTag.Items} nests {@code contents} (below 1.20.5, no container
     * component).
     */
    public static ItemStack shulkerHolding(ItemStack... contents) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.put("Items", ItemFixtures.items(contents));
        shulker.getOrCreateTag().put("BlockEntityTag", blockEntityTag);
        return shulker;
    }

    /** The captured {@code Items} holder tag for {@code stacks}, serialized through {@code sink}. */
    public static CompoundTag holderOf(ContainerSink sink, ItemStack... stacks) {
        NonNullList<ItemStack> items = NonNullList.withSize(stacks.length, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            items.set(i, stacks[i]);
        }
        return sink.captureItems(items);
    }

    /** A holder whose items are filled maps referencing {@code mapIds}, in order. */
    public static CompoundTag holderReferencing(ContainerSink sink, int... mapIds) {
        ItemStack[] maps = new ItemStack[mapIds.length];
        for (int i = 0; i < mapIds.length; i++) {
            maps[i] = filledMap(mapIds[i]);
        }
        return holderOf(sink, maps);
    }
}
