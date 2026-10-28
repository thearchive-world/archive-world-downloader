// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved) {
        // save puts the live colors array into its compound rather than a copy of it, and vanilla's map packet handler
        // keeps writing received pixels into that same array.
        if (saved.dimension != null) {
            return saved.save(new CompoundTag()).copy();
        }
        // A filled map can reach the client with a null dimension, which save dereferences (this.dimension.getId()).
        // Stand in the overworld type for the serialize so the image still writes, then restore so the live client map
        // is left as received.
        saved.dimension = DimensionType.field_18954;
        try {
            return saved.save(new CompoundTag()).copy();
        } finally {
            saved.dimension = null;
        }
    }
}
