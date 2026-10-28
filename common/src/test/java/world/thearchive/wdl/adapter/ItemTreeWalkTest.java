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
    void walkVisitsAnItemsOwnTag() {
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
    void walkRecursesIntoAnItemsOwnItemsList() {
        NBTTagCompound nestedTag = new NBTTagCompound();
        NBTTagList ownItems = new NBTTagList();
        ownItems.appendTag(itemWithTag(nestedTag));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Items", ownItems);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in an item's own Items list is visited");
    }
}
