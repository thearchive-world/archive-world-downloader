// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.adapter.MapSink;

public final class MapSinkImpl implements MapSink {
    @Override
    public Tag serializeMap(MapItemSavedData saved, RegistryAccess registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return MapItemSavedData.CODEC.encodeStart(ops, saved).getOrThrow();
    }
}
