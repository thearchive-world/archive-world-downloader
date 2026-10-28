// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public interface MapSink {
    /**
     * Serialize {@code saved} into the inner {@code "data"} {@link Tag} of the map's save file.
     */
    Tag serializeMap(MapItemSavedData saved, RegistryAccess registries);
}
