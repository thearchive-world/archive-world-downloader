// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTBase;
import net.minecraft.world.storage.MapData;

public interface MapSink {
    /**
     * Serialize {@code saved} into the inner {@code "data"} {@link NBTBase} a {@code data/map_<id>.dat} holds.
     */
    NBTBase serializeMap(MapData saved);
}
