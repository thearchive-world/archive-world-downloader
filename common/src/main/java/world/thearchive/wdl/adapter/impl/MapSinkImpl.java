// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.MapData;

import world.thearchive.wdl.adapter.MapSink;

/**
 * {@code writeToNBT} puts the live {@code colors} array into its compound rather than a copy of it, and vanilla's map
 * packet handler keeps writing received pixels into that same array, so the serialized tag is copied to detach it from
 * the live map.
 */
public final class MapSinkImpl implements MapSink {
    @Override
    public NBTBase serializeMap(MapData saved) {
        return saved.writeToNBT(new NBTTagCompound()).copy();
    }
}
