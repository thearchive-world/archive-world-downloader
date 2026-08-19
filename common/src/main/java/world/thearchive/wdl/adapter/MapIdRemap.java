// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The write half of {@link MapIdCollector}: rewrites every referenced map id in a serialized item list to a
 * resolver-supplied archive id, recursing into a shulker box (the {@code BlockEntityTag.Items} list) and a bundle (the
 * {@code Items} list) over the shared {@link ItemTreeWalk}. The session-local id a renumbering server assigns is
 * replaced by the content-stable archive id ({@link world.thearchive.wdl.core.MapManifest}) so the item renders the
 * right picture in the download.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time stashes), never on a live
 * {@code ItemStack}, and reads/writes the id through the raw {@code "map"} int tag. The rewrite is <em>not</em>
 * idempotent: a second pass would read an already-written archive id as if it were a session id and re-resolve it, so a
 * holder must be remapped exactly once, at the point it is consumed (the caller's responsibility).
 */
final class MapIdRemap {
    private MapIdRemap() {}

    /**
     * Rewrite every map id referenced by the items in {@code holder}'s {@code listKey} list (each list element is item
     * NBT, with or without a leading {@code "Slot"}) through {@code resolver}, recursing into nested containers and
     * bundles. A missing or non-list {@code listKey} is a no-op. {@code holder} is mutated in place.
     */
    public static void remapFromItemList(CompoundTag holder, String listKey, IntUnaryOperator resolver) {
        if (holder.get(listKey) instanceof ListTag) {
            ListTag list = (ListTag) holder.get(listKey);
            ItemTreeWalk.walkList(list, tag -> remapMapId(tag, resolver));
        }
    }

    /**
     * Rewrite every map id referenced by a single item compound ({@code {id, Count, tag}}), recursing into nested
     * containers and bundles. The entry point for an entity-borne single item (an item frame's {@code "Item"}, a
     * dropped item entity's {@code "Item"}); {@code item} is mutated in place and must be remapped exactly once.
     */
    public static void remapItem(CompoundTag item, IntUnaryOperator resolver) {
        ItemTreeWalk.walkItem(item, tag -> remapMapId(tag, resolver));
    }

    /** Rewrite the top-level {@code "map"} id on {@code tag} through {@code resolver}, when present. */
    private static void remapMapId(CompoundTag tag, IntUnaryOperator resolver) {
        if (tag.contains("map", 99)) {
            int old = tag.getInt("map");
            int mapped = resolver.applyAsInt(old);
            if (mapped != old) {
                tag.putInt("map", mapped);
            }
        }
    }
}
