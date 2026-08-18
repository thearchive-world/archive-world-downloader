// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

/**
 * 1.17.1 map sink: serializes a client {@code MapItemSavedData} via vanilla's own {@code MapItemSavedData.save} (the
 * pre-1.21.5 {@code SavedData} write), so the captured inner {@code "data"} tag is byte-for-byte what a vanilla
 * {@code data/map_<id>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code MapItemSavedData}), mirroring {@code ContainerSink}'s lift; the
 * headless round-trip re-parse via the same call is the automated guard.
 */
public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved, RegistryAccess registries) {
        // save writes the inner map "data" compound the band-agnostic MapDataWriter then wraps as
        // {data, DataVersion} and gzips.
        return saved.save(new CompoundTag());
    }
}
