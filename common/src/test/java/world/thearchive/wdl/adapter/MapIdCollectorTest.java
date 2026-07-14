// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.bundleHolding;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderOf;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.shulkerHolding;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The headless guard for the pure id-collection: {@link MapIdCollector} walks a serialized item list and collects every
 * {@code minecraft:map_id} it references, recursing into a shulker box (the {@code minecraft:container} component) and
 * a bundle (the {@code minecraft:bundle_contents} component) the way {@link ItemLocationScrub} does, and skipping
 * non-map items. Real {@link ItemStack}s serialized via the production {@link ContainerSink#captureItems} drive it, so
 * neither a live menu nor a {@code Level} is needed; the live {@code getMapData} resolution and the item-frame walk are
 * not exercised headless.
 */
class MapIdCollectorTest {
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
    void collectsTopLevelNestedShulkerAndBundleMapIdsSkippingNonMaps() {
        CompoundTag holder = holderOf(sink, registries, filledMap(5), shulkerHolding(filledMap(7)),
                bundleHolding(filledMap(9)), new ItemStack(Items.DIAMOND, 3));

        assertEquals(Set.of(5, 7, 9), collect(holder),
                "exactly the three referenced map ids (top-level, nested in a shulker, nested in a bundle); "
                        + "the non-map item contributes none");
    }

    @Test
    void collectsAnImagelessReferencedIdHigherThanImagedOnesForTheIdcountsFloor() {
        // A chest map referenced but never carried (imageless) is still collected, so idcounts floors
        // above it. Here ids 1 and 2 stand for imaged maps and 50 for the imageless chest map; the collection
        // (hence the idcounts floor = max) must include 50, not stop at the imaged max of 2.
        Set<Integer> ids = collect(holderOf(sink, registries, filledMap(1), filledMap(2), filledMap(50)));

        assertEquals(Set.of(1, 2, 50), ids);
        assertEquals(50, Collections.max(ids), "idcounts floors at the max referenced candidate id");
    }

    @Test
    void aHolderWithNoMapReferencesCollectsNothing() {
        Set<Integer> ids = collect(
                holderOf(sink, registries, new ItemStack(Items.DIAMOND), new ItemStack(Items.STICK, 2)));

        assertTrue(ids.isEmpty(),
                "no map id referenced, so nothing is collected and no data/ file is written (the no-op path)");
    }

    @Test
    void aMissingItemsListCollectsNothing() {
        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(new CompoundTag(), "Items", ids);

        assertTrue(ids.isEmpty(), "a holder with no Items list is a no-op");
    }
}
