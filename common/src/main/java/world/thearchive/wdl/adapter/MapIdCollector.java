// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * Reusable, band-agnostic collection of the {@code minecraft:map_id}s a serialized item list references: collects the
 * top-level {@code map_id} on each item's components, over the shared {@link ItemTreeWalk} that recurses into a shulker
 * box (the {@code minecraft:container} component) and a bundle (the {@code minecraft:bundle_contents} component). The
 * pure, headless-tested half of the filled-map enumeration: the live {@code ItemStack}/{@code ItemFrame} walk and the
 * {@code getMapData} resolution are MC-typed and gate-validated.
 *
 * <p>Operates only on already-serialized NBT (our own captured copies of the open-time container / vehicle / ender
 * stashes), never on a live {@code ItemStack}. Reads the id via the band-stable
 * {@code MapId.CODEC.parse(NbtOps.INSTANCE, ...)} (the {@link EntityMerge} {@code readUuid} precedent) rather than an
 * {@code IntTag} value accessor, whose name drifts across the 1.21.5 codec cut, so it is byte-identical across the era
 * bands. Used both at finish (the stashes) and as the enumeration's automated guard.
 */
final class MapIdCollector {
    private static final String MAP_ID = "minecraft:map_id";

    private MapIdCollector() {}

    /**
     * Add every {@code map_id} referenced by the items in {@code holder}'s {@code listKey} list (each list element is
     * item NBT, with or without a leading {@code "Slot"}) into {@code out}, recursing into nested containers and
     * bundles. A missing or non-list {@code listKey} is a no-op. {@code holder} is not mutated.
     */
    public static void collectFromItemList(CompoundTag holder, String listKey, Set<Integer> out) {
        if (holder.get(listKey) instanceof ListTag list) {
            ItemTreeWalk.walkList(list, components -> collectMapId(components, out));
        }
    }

    /** Add the top-level {@code map_id} on {@code components} to {@code out}, when present. */
    private static void collectMapId(CompoundTag components, Set<Integer> out) {
        Tag mapId = components.get(MAP_ID);
        if (mapId != null) {
            MapId.CODEC.parse(NbtOps.INSTANCE, mapId).result().ifPresent(id -> out.add(id.id()));
        }
    }
}
