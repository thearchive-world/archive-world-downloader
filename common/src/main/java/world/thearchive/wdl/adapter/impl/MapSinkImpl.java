// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

/**
 * 1.21.11 map sink: serializes a client {@code MapItemSavedData} via vanilla's own
 * {@code MapItemSavedData.CODEC.encodeStart} (the codec layer of the 1.21.5 {@code SavedData} to {@code Codec} cut, the
 * exact call {@code DimensionDataStorage} makes), so the captured inner {@code "data"} tag is byte-for-byte what a
 * vanilla {@code data/map_<id>.dat} would hold.
 *
 * <p>The single step is client-coupled (a live {@code MapItemSavedData}), mirroring {@code ContainerSink}'s lift; the
 * headless round-trip re-parse via the same codec is the automated guard.
 */
public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved, RegistryAccess registries) {
        // Lift of DimensionDataStorage.encodeUnchecked's inner encode: the RegistryOps the vanilla writer uses
        // (createSerializationContext(NbtOps)) over the band's MapItemSavedData.CODEC, yielding the same inner
        // "data" tag the band-agnostic MapDataWriter then wraps as {data, DataVersion} and gzips.
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return MapItemSavedData.CODEC.encodeStart(ops, saved).getOrThrow();
    }
}
