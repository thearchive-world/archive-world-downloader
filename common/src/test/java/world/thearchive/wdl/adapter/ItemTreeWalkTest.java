// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * Pins ItemTreeWalk's nesting contract: the walk applies the leaf action to an item's own components and recurses into
 * the items nested in a shulker box container, a bundle, and a sulfur cube. It drives the walk over hand-built NBT
 * keyed by the component id strings, so it needs no live component and holds on every band, including one whose
 * Minecraft has no sulfur cube. The visited compounds are matched by identity, so the assertions stay clear of the
 * band-varying CompoundTag accessors.
 */
class ItemTreeWalkTest {
    private static CompoundTag itemWithComponents(CompoundTag components) {
        CompoundTag item = new CompoundTag();
        item.put("components", components);
        return item;
    }

    private static Set<CompoundTag> visitedComponents(CompoundTag item) {
        Set<CompoundTag> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ItemTreeWalk.walkItem(item, visited::add);
        return visited;
    }

    @Test
    void walkVisitsAnItemsOwnComponents() {
        CompoundTag components = new CompoundTag();
        assertTrue(visitedComponents(itemWithComponents(components)).contains(components),
                "the walk applies the leaf action to the item's own components");
    }

    @Test
    void walkRecursesIntoContainer() {
        CompoundTag nestedComponents = new CompoundTag();
        CompoundTag slot = new CompoundTag();
        slot.put("item", itemWithComponents(nestedComponents));
        ListTag container = new ListTag();
        container.add(slot);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:container", container);
        assertTrue(visitedComponents(itemWithComponents(components)).contains(nestedComponents),
                "an item nested in a shulker box container is visited");
    }

    @Test
    void walkRecursesIntoBundle() {
        CompoundTag nestedComponents = new CompoundTag();
        ListTag bundle = new ListTag();
        bundle.add(itemWithComponents(nestedComponents));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:bundle_contents", bundle);
        assertTrue(visitedComponents(itemWithComponents(components)).contains(nestedComponents),
                "an item nested in a bundle is visited");
    }

    @Test
    void walkRecursesIntoSulfurCube() {
        CompoundTag nestedComponents = new CompoundTag();
        CompoundTag components = new CompoundTag();
        components.put("minecraft:sulfur_cube_content", itemWithComponents(nestedComponents));
        assertTrue(visitedComponents(itemWithComponents(components)).contains(nestedComponents),
                "an item nested in a sulfur cube is visited");
    }
}
