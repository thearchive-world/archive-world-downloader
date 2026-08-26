// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * The write half of {@link MapIdCollector}: rewrites every referenced map id in a serialized item list to a
 * resolver-supplied archive id, recursing into a shulker box (the {@code BlockEntityTag.Items} list) and a bundle (the
 * {@code Items} list) over the shared {@link ItemTreeWalk}. The session-local id a renumbering server assigns is
 * replaced by the content-stable archive id ({@link world.thearchive.wdl.core.MapManifest}) so the item renders the
 * right picture in the download.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time stashes), never on a live
 * {@code ItemStack}. At this band the id is the item-level {@code Damage} short (the map's ItemStack metadata) behind
 * the {@code id == "minecraft:filled_map"} identity gate, not the inner {@code tag."map"}. The rewrite is <em>not</em>
 * idempotent: a second pass would read an already-written archive id as if it were a session id and re-resolve it, so a
 * holder must be remapped exactly once, at the point it is consumed (the caller's responsibility).
 */
final class MapIdRemap {
    private static final String FILLED_MAP_ID = "minecraft:filled_map";

    private MapIdRemap() {}

    /**
     * Rewrite every map id referenced by the items in {@code holder}'s {@code listKey} list (each list element is item
     * NBT, with or without a leading {@code "Slot"}) through {@code resolver}, recursing into nested containers and
     * bundles. A missing or non-list {@code listKey} is a no-op. {@code holder} is mutated in place.
     */
    public static void remapFromItemList(NBTTagCompound holder, String listKey, IntUnaryOperator resolver) {
        if (holder.getTag(listKey) instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) holder.getTag(listKey);
            ItemTreeWalk.forEachItem(list, item -> remapMapId(item, resolver));
        }
    }

    /**
     * Rewrite every map id referenced by a single item compound ({@code {id, Count, Damage, tag}}), recursing into
     * nested containers and bundles. The entry point for an entity-borne single item (an item frame's {@code "Item"}, a
     * dropped item entity's {@code "Item"}); {@code item} is mutated in place and must be remapped exactly once.
     */
    public static void remapItem(NBTTagCompound item, IntUnaryOperator resolver) {
        ItemTreeWalk.forEachItem(item, nested -> remapMapId(nested, resolver));
    }

    /**
     * Rewrite the filled map's id through {@code resolver} when {@code item} is a filled map. The id is the item-level
     * {@code Damage} short, read and written only behind the item identity gate: {@code Damage} is the universal item
     * field, so a blanket rewrite would corrupt a non-map item's durability or type, and {@code Damage == 0} collides
     * with {@code map_0}.
     */
    private static void remapMapId(NBTTagCompound item, IntUnaryOperator resolver) {
        if (!FILLED_MAP_ID.equals(item.getString("id"))) {
            return;
        }
        int old = item.getShort("Damage");
        int mapped = resolver.applyAsInt(old);
        if (mapped != old) {
            item.setShort("Damage", (short) mapped);
        }
    }
}
