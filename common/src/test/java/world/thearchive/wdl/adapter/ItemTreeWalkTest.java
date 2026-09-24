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
 * Pins {@code ItemTreeWalk}'s nesting contract: below 1.20.5 an item is {@code {id, Count, tag}}, and the walk applies
 * the leaf action to the item's own {@code tag} compound, then recurses into the items nested under a container item's
 * {@code tag.BlockEntityTag.Items} and under an item's own {@code tag.Items}. It drives the walk over hand-built NBT
 * keyed by those pre-component keys. The visited compounds are matched by identity, so the assertions stay clear of the
 * band-varying {@code CompoundTag} accessors.
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
    void walkVisitsAnItemsOwnTag() {
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
    void walkRecursesIntoAnItemsOwnItemsList() {
        CompoundTag nestedTag = new CompoundTag();
        ListTag ownItems = new ListTag();
        ownItems.add(itemWithTag(nestedTag));
        CompoundTag tag = new CompoundTag();
        tag.put("Items", ownItems);
        assertTrue(visitedTags(itemWithTag(tag)).contains(nestedTag),
                "an item nested in an item's own Items list is visited");
    }
}
