// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

/**
 * Pins ItemTreeWalk's nesting contract: below 1.20.5 an item is {@code {id, Count, tag}}, and the walk applies the leaf
 * action to the item's own {@code tag} compound, then recurses into the items nested in a shulker box
 * ({@code tag.BlockEntityTag.Items}) and a bundle ({@code tag.Items}). It drives the walk over hand-built NBT keyed by
 * those pre-component keys. The visited compounds are matched by identity, so the assertions stay clear of the
 * band-varying NBTTagCompound accessors.
 */
class ItemTreeWalkTest {
    private static NBTTagCompound itemWithTag(NBTTagCompound tag) {
        NBTTagCompound item = new NBTTagCompound();
        item.setTag("tag", tag);
        return item;
    }

    private static Set<NBTTagCompound> visitedTags(NBTTagCompound item) {
        Set<NBTTagCompound> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ItemTreeWalk.walkItem(item, visited::add);
        return visited;
    }

    @Test
    void walkVisitsAnItemsOwnComponents() {
        NBTTagCompound tag = new NBTTagCompound();
        assertTrue(visitedTags(itemWithTag(tag)).contains(tag),
                "the walk applies the leaf action to the item's own tag");
    }

    @Test
    void walkRecursesIntoContainer() {
        NBTTagCompound nestedTag = new NBTTagCompound();
        NBTTagList shulkerItems = new NBTTagList();
        shulkerItems.appendTag(itemWithTag(nestedTag));
        NBTTagCompound blockEntityTag = new NBTTagCompound();
        blockEntityTag.setTag("Items", shulkerItems);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("BlockEntityTag", blockEntityTag);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in a shulker box container is visited");
    }

    @Test
    void walkRecursesIntoBundle() {
        NBTTagCompound nestedTag = new NBTTagCompound();
        NBTTagList bundleItems = new NBTTagList();
        bundleItems.appendTag(itemWithTag(nestedTag));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Items", bundleItems);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in a bundle is visited");
    }
}
