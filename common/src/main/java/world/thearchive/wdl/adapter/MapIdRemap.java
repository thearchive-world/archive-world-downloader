// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * The write half of {@link MapIdCollector}: rewrites every referenced {@code minecraft:map_id} in a serialized item
 * list to a resolver-supplied archive id, recursing into a shulker box (the {@code minecraft:container} component) and
 * a bundle (the {@code minecraft:bundle_contents} component) over the shared {@link ItemTreeWalk}. The session-local id
 * a renumbering server assigns is replaced by the content-stable archive id
 * ({@link world.thearchive.wdl.core.MapManifest}) so the item renders the right picture in the download.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time stashes), never on a live
 * {@code ItemStack}, and reads/writes the id through the band-stable {@code MapId.CODEC} (the {@link MapIdCollector}
 * precedent) so it is byte-identical across the 1.21.5 codec cut. The rewrite is <em>not</em> idempotent: a second pass
 * would read an already-written archive id as if it were a session id and re-resolve it, so a holder must be remapped
 * exactly once, at the point it is consumed (the caller's responsibility).
 */
final class MapIdRemap {
    private static final String MAP_ID = "minecraft:map_id";

    private MapIdRemap() {}

    /**
     * Rewrite every {@code map_id} referenced by the items in {@code holder}'s {@code listKey} list (each list element
     * is item NBT, with or without a leading {@code "Slot"}) through {@code resolver}, recursing into nested containers
     * and bundles. A missing or non-list {@code listKey} is a no-op. {@code holder} is mutated in place.
     */
    public static void remapFromItemList(CompoundTag holder, String listKey, IntUnaryOperator resolver) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, components -> remapMapId(components, resolver));
        }
    }

    /**
     * Rewrite every {@code map_id} referenced by a single item compound ({@code {id, count, components}}), recursing
     * into nested containers and bundles. The entry point for an entity-borne single item (an item frame's
     * {@code "Item"}, a dropped item entity's {@code "Item"}); {@code item} is mutated in place and must be remapped
     * exactly once.
     */
    public static void remapItem(CompoundTag item, IntUnaryOperator resolver) {
        ItemTreeWalk.walkItem(item, components -> remapMapId(components, resolver));
    }

    /** Rewrite the top-level {@code map_id} on {@code components} through {@code resolver}, when present. */
    private static void remapMapId(CompoundTag components, IntUnaryOperator resolver) {
        Tag mapId = components.get(MAP_ID);
        if (mapId != null) {
            MapId.CODEC.parse(NbtOps.INSTANCE, mapId).result().ifPresent(id -> {
                int archiveId = resolver.applyAsInt(id.id());
                MapId.CODEC.encodeStart(NbtOps.INSTANCE, new MapId(archiveId)).result()
                        .ifPresent(encoded -> components.put(MAP_ID, encoded));
            });
        }
    }
}
