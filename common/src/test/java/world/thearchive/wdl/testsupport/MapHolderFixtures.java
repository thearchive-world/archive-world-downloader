// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.saveddata.maps.MapId;

import world.thearchive.wdl.adapter.ContainerSink;

/**
 * Shared map-item-holder NBT fixtures for the map archive tests: a filled-map stack, a shulker or bundle nesting one,
 * and the captured {@code Items} holder they serialize into. Hoisted here so the id collector, remap, and archive tests
 * share one copy rather than each carrying its own.
 */
public final class MapHolderFixtures {
    private MapHolderFixtures() {}

    /** A filled-map stack carrying {@code id} in its {@code minecraft:map_id} component. */
    public static ItemStack filledMap(int id) {
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(id));
        return map;
    }

    /** A shulker box whose {@code minecraft:container} component nests {@code contents}. */
    public static ItemStack shulkerHolding(ItemStack... contents) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return shulker;
    }

    /** A bundle whose {@code minecraft:bundle_contents} component nests {@code contents}. */
    public static ItemStack bundleHolding(ItemStack... contents) {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(contents)));
        return bundle;
    }

    /** The captured {@code Items} holder tag for {@code stacks}, serialized through {@code sink}. */
    public static CompoundTag holderOf(ContainerSink sink, RegistryAccess registries, ItemStack... stacks) {
        NonNullList<ItemStack> items = NonNullList.withSize(stacks.length, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            items.set(i, stacks[i]);
        }
        return sink.captureItems(items, registries);
    }

    /** A holder whose items are filled maps referencing {@code mapIds}, in order. */
    public static CompoundTag holderReferencing(ContainerSink sink, RegistryAccess registries, int... mapIds) {
        ItemStack[] maps = new ItemStack[mapIds.length];
        for (int i = 0; i < mapIds.length; i++) {
            maps[i] = filledMap(mapIds[i]);
        }
        return holderOf(sink, registries, maps);
    }
}
