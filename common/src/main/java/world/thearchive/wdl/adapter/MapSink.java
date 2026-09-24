// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Per-band map-serialize axis: encode a client {@link MapItemSavedData} into the inner {@code "data"} tag of a map's
 * {@code data/} save file.
 *
 * <p>Mirrors {@link PlayerSink}'s lift. The single step is client-coupled (a live {@code MapItemSavedData} resolved
 * from the client's map store); the headless guard is the round-trip re-parse through the band's own map read.
 */
public interface MapSink {
    /**
     * Serialize {@code saved} into the inner {@code "data"} {@link Tag} of the map's save file: the vanilla
     * {@code MapItemSavedData} persistence ({@code colors}/{@code dimension}/{@code scale}/...), via the band's own
     * serialize call. Lock-agnostic: it encodes whatever map the session hands it, and the auto-lock decision lives in
     * the session.
     */
    Tag serializeMap(MapItemSavedData saved, RegistryAccess registries);
}
