// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.containerHolding;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.filledMap;
import static world.thearchive.wdl.testsupport.MapHolderFixtures.holderOf;

import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.ContainerSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

class MapIdCollectorTest {
    private final ContainerSink sink = new ContainerSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static Set<Integer> collect(NBTTagCompound holder) {
        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(holder, "Items", ids);
        return ids;
    }

    @Test
    void collectsTopLevelAndNestedContainerMapIdsSkippingNonMaps() {
        NBTTagCompound holder = holderOf(sink, filledMap(5), containerHolding(filledMap(7)),
                new ItemStack(Items.DIAMOND, 3));

        assertEquals(ImmutableSet.of(5, 7), collect(holder),
                "exactly the two referenced map ids (top-level and nested in a container item); "
                        + "the non-map item contributes none");
    }

    @Test
    void collectsAnImagelessReferencedIdHigherThanImagedOnesForTheIdcountsFloor() {
        Set<Integer> ids = collect(holderOf(sink, filledMap(1), filledMap(2), filledMap(50)));

        assertEquals(ImmutableSet.of(1, 2, 50), ids);
        assertEquals(50, Collections.max(ids), "idcounts floors at the max referenced candidate id");
    }

    @Test
    void aHolderWithNoMapReferencesCollectsNothing() {
        Set<Integer> ids = collect(
                holderOf(sink, new ItemStack(Items.DIAMOND), new ItemStack(Items.STICK, 2)));

        assertTrue(ids.isEmpty(),
                "no map id referenced, so nothing is collected and no data/ file is written (the no-op path)");
    }

    @Test
    void aMissingItemsListCollectsNothing() {
        Set<Integer> ids = new LinkedHashSet<>();
        MapIdCollector.collectFromItemList(new NBTTagCompound(), "Items", ids);

        assertTrue(ids.isEmpty(), "a holder with no Items list is a no-op");
    }
}
