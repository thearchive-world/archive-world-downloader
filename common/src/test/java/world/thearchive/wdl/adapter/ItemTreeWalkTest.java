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
 * Pins ItemTreeWalk's nesting contract: below 1.20.5 an item is {@code {id, Count, tag}}, and the walk applies the leaf
 * action to the item's own {@code tag} compound, then recurses into the items nested in a shulker box
 * ({@code tag.BlockEntityTag.Items}) and a bundle ({@code tag.Items}). It drives the walk over hand-built NBT keyed by
 * those pre-component keys. The visited compounds are matched by identity, so the assertions stay clear of the
 * band-varying CompoundTag accessors.
 */
class ItemTreeWalkTest {
    private static CompoundTag itemWithTag(CompoundTag tag) {
        CompoundTag item = new CompoundTag();
        item.put("tag", tag);
        return item;
    }

    private static Set<CompoundTag> visitedTags(CompoundTag item) {
        Set<CompoundTag> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ItemTreeWalk.walkItem(item, visited::add);
        return visited;
    }

    @Test
    void walkVisitsAnItemsOwnComponents() {
        CompoundTag tag = new CompoundTag();
        assertTrue(visitedTags(itemWithTag(tag)).contains(tag),
                "the walk applies the leaf action to the item's own tag");
    }

    @Test
    void walkRecursesIntoContainer() {
        CompoundTag nestedTag = new CompoundTag();
        ListTag shulkerItems = new ListTag();
        shulkerItems.add(itemWithTag(nestedTag));
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.put("Items", shulkerItems);
        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntityTag);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in a shulker box container is visited");
    }

    @Test
    void walkRecursesIntoBundle() {
        CompoundTag nestedTag = new CompoundTag();
        ListTag bundleItems = new ListTag();
        bundleItems.add(itemWithTag(nestedTag));
        CompoundTag tag = new CompoundTag();
        tag.put("Items", bundleItems);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in a bundle is visited");
    }
}
