// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.bundleHolding;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderOf;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.shulkerHolding;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the id remap: {@link MapIdRemap} mirrors {@link MapIdCollector} (collect becomes set),
 * rewriting every referenced {@code minecraft:map_id} to a resolver-supplied archive id across the same surfaces
 * (top-level, a shulker's {@code minecraft:container}, a bundle's {@code minecraft:bundle_contents}). Collecting after
 * the remap is the oracle: the remapped set must equal the resolver applied to the original set, proving the walk
 * touches exactly the ids the collector reads.
 */
class MapIdRemapTest {
    private static RegistryAccess registries;
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static Set<Integer> collect(CompoundTag holder) {
        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(holder, "Items", ids);
        return ids;
    }

    @Test
    void remapsEveryReferencedIdAcrossAllSurfacesSkippingNonMaps() {
        CompoundTag holder = holderOf(sink, registries, filledMap(5), shulkerHolding(filledMap(7)),
                bundleHolding(filledMap(9)), new ItemStack(Items.DIAMOND, 3));

        MapIdRemap.remapFromItemList(holder, "Items", id -> id + 100);

        assertEquals(Set.of(105, 107, 109), collect(holder),
                "every referenced id, nested or not, is rewritten by the resolver; the diamond is untouched");
    }

    @Test
    void remapTouchesExactlyTheIdsTheCollectorReads() {
        CompoundTag holder = holderOf(sink, registries, filledMap(5), shulkerHolding(filledMap(7)),
                bundleHolding(filledMap(9)));
        assertEquals(Set.of(5, 7, 9), collect(holder), "the pre-remap oracle");

        Map<Integer, Integer> table = Map.of(5, 0, 7, 1, 9, 2);
        MapIdRemap.remapFromItemList(holder, "Items", table::get);

        assertEquals(Set.of(0, 1, 2), collect(holder), "collect after remap equals the resolver applied");
    }

    @Test
    void theSameIdInTwoPlacesRemapsConsistently() {
        CompoundTag holder = holderOf(sink, registries, filledMap(42), shulkerHolding(filledMap(42)));

        MapIdRemap.remapFromItemList(holder, "Items", id -> id + 100);

        assertEquals(Set.of(142), collect(holder), "both references resolve to the one archive id");
    }

    @Test
    void remapItemRewritesSingleItemInPlace() {
        // The entry point the entity path uses for an item frame's single "Item" (not a list).
        CompoundTag holder = holderOf(sink, registries, filledMap(5));
        CompoundTag item = (CompoundTag) ((ListTag) holder.get("Items")).get(0);

        MapIdRemap.remapItem(item, id -> id + 100);

        assertEquals(Set.of(105), collect(holder), "the single-item remap rewrote the id in place");
    }

    @Test
    void aMissingItemsListDoesNothing() {
        CompoundTag empty = new CompoundTag();
        MapIdRemap.remapFromItemList(empty, "Items", id -> id + 100);
        assertTrue(collect(empty).isEmpty());
    }

    @Test
    void aHolderWithNoMapReferencesIsLeftAlone() {
        CompoundTag holder = holderOf(sink, registries, new ItemStack(Items.DIAMOND), new ItemStack(Items.STICK, 2));

        MapIdRemap.remapFromItemList(holder, "Items", id -> id + 100);

        assertTrue(collect(holder).isEmpty(), "no map id to rewrite");
    }
}
