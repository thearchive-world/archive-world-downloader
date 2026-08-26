// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import net.minecraft.util.NonNullList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;

import world.thearchive.wdl.adapter.ContainerSink;

/**
 * Shared map-item-holder NBT fixtures for the map archive tests: a filled-map stack, a shulker nesting one, and the
 * captured {@code Items} holder they serialize into. Hoisted here so the id collector, remap, and archive tests share
 * one copy rather than each carrying its own.
 */
public final class MapHolderFixtures {
    private MapHolderFixtures() {}

    /**
     * A filled-map stack carrying {@code id} as its item-level {@code Damage} (this band's map id, an item metadata
     * value, not an inner {@code tag."map"} compound).
     */
    public static ItemStack filledMap(int id) {
        return new ItemStack(Items.FILLED_MAP, 1, id);
    }

    /**
     * A shulker box whose {@code tag.BlockEntityTag.Items} nests {@code contents}. At this band shulker boxes are
     * sixteen per-color blocks with no colorless variant; the color is incidental to the fixture.
     */
    public static ItemStack shulkerHolding(ItemStack... contents) {
        ItemStack shulker = new ItemStack(Blocks.PURPLE_SHULKER_BOX);
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag("Items", ItemFixtures.items(contents));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("BlockEntityTag", blockEntityTag);
        shulker.setTagCompound(tag);
        return shulker;
    }

    /** The captured {@code Items} holder tag for {@code stacks}, serialized through {@code sink}. */
    public static NBTTagCompound holderOf(ContainerSink sink, ItemStack... stacks) {
        NonNullList<ItemStack> items = NonNullList.withSize(stacks.length, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            items.set(i, stacks[i]);
        }
        return sink.captureItems(items);
    }

    /** A holder whose items are filled maps referencing {@code mapIds}, in order. */
    public static NBTTagCompound holderReferencing(ContainerSink sink, int... mapIds) {
        ItemStack[] maps = new ItemStack[mapIds.length];
        for (int i = 0; i < mapIds.length; i++) {
            maps[i] = filledMap(mapIds[i]);
        }
        return holderOf(sink, maps);
    }
}
