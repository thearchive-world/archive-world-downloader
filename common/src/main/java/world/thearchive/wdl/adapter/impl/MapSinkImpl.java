// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

/**
 * 1.14.4 map sink: serializes a client {@code MapItemSavedData} via vanilla's own {@code MapItemSavedData.save} (the
 * pre-1.21.5 {@code SavedData} write), so the captured inner {@code "data"} tag is byte-for-byte what a vanilla
 * {@code data/map_<id>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code MapItemSavedData}), mirroring {@code ContainerSink}'s lift; the
 * headless round-trip re-parse via the same call is the automated guard.
 */
public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved) {
        // save writes the inner map "data" compound the band-agnostic MapDataWriter then wraps as
        // {data, DataVersion} and gzips. It puts the live colors array into that compound rather than a copy of
        // it, and vanilla's map packet handler keeps writing received pixels into that same array.
        if (saved.dimension != null) {
            return saved.save(new CompoundTag()).copy();
        }
        // A version-bridging proxy (ViaVersion/ViaBackwards fronting a newer server) can relay a filled map to the
        // client with a null dimension, which save dereferences (this.dimension.getId()). Stand in the overworld
        // type for the serialize so the image still writes, then restore so the live client map is left as received,
        // the way the session leaves its locked flag alone.
        saved.dimension = DimensionType.OVERWORLD;
        try {
            return saved.save(new CompoundTag()).copy();
        } finally {
            saved.dimension = null;
        }
    }
}
