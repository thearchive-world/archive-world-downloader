// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved, RegistryAccess registries) {
        // save puts the live colors array into its compound rather than a copy of it, and vanilla's map packet handler
        // keeps writing received pixels into that same array.
        return saved.save(new CompoundTag(), registries).copy();
    }
}
