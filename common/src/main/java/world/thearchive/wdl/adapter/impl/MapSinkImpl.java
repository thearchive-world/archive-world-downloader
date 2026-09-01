// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.MapData;

import world.thearchive.wdl.adapter.MapSink;

/**
 * 1.11.2 map sink: serializes a client {@code MapData} via vanilla's own {@code MapData.writeToNBT} (the classic MCP
 * SavedData write), so the captured inner {@code "data"} tag is byte-for-byte what a vanilla {@code data/map_<id>.dat}
 * would hold. {@code writeToNBT} puts the live {@code colors} array into that compound rather than a copy of it, and
 * vanilla's map packet handler keeps writing received pixels into that same array, so the serialized tag is copied to
 * detach it from the live map.
 *
 * <p>The single step is client-coupled (a live {@code MapData}), mirroring {@code ContainerSink}'s lift; the headless
 * round-trip re-parse via the same call is the automated guard.
 */
public final class MapSinkImpl implements MapSink {
    @Override
    public NBTBase serializeMap(MapData saved) {
        return saved.writeToNBT(new NBTTagCompound()).copy();
    }
}
