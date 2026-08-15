// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Reusable, band-agnostic collection of the map ids a serialized item list references: collects the top-level
 * {@code "map"} id on each item's tag, over the shared {@link ItemTreeWalk} that recurses into a shulker box (the
 * {@code BlockEntityTag.Items} list) and a bundle (the {@code Items} list). The pure, headless-tested half of the
 * filled-map enumeration: the live {@code ItemStack}/{@code ItemFrame} walk and the {@code getMapData} resolution are
 * MC-typed and gate-validated.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time container / vehicle / ender
 * stashes), never on a live {@code ItemStack}. Used both at finish (the stashes) and as the enumeration's automated
 * guard.
 */
final class MapIdCollector {
    private MapIdCollector() {}

    /**
     * Add every map id referenced by the items in {@code holder}'s {@code listKey} list (each list element is item NBT,
     * with or without a leading {@code "Slot"}) into {@code out}, recursing into nested containers and bundles. A
     * missing or non-list {@code listKey} is a no-op. {@code holder} is not mutated.
     */
    public static void collectFromItemList(CompoundTag holder, String listKey, Set<Integer> out) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, tag -> collectMapId(tag, out));
        }
    }

    /** Add the top-level {@code "map"} id on {@code tag} to {@code out}, when present. */
    private static void collectMapId(CompoundTag tag, Set<Integer> out) {
        if (tag.contains("map", 99)) {
            out.add(tag.getInt("map"));
        }
    }
}
