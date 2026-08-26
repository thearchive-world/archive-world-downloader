// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.nbt.NBTBase;
import net.minecraft.world.storage.MapData;

/**
 * Per-band map-serialize axis: encode a client {@link MapData} into the inner {@code "data"} tag of a
 * {@code data/map_<id>.dat} save surface. At this band the serialize is vanilla's own {@code MapData.writeToNBT} (the
 * classic MCP SavedData write), while the {@code {data}} gzip envelope (no {@code DataVersion} below 1.13) and the
 * uncompressed root {@code {map: short}} idcounts shape are band-agnostic here ({@link MapDataWriter}).
 *
 * <p>Mirrors {@link PlayerSink}'s lift. The single step is client-coupled (a live {@code MapData} resolved from the
 * world's map store); the headless guard is the round-trip re-parse via the band's own {@code MapData} persistence.
 */
public interface MapSink {
    /**
     * Serialize {@code saved} into the inner {@code "data"} {@link NBTBase} a {@code data/map_<id>.dat} holds: the
     * vanilla {@code MapData} persistence ({@code colors}/{@code dimension}/{@code scale}/{@code locked}/...), via the
     * band's own {@code writeToNBT}. Lock-agnostic: it encodes whatever map the session hands it, and the auto-lock
     * decision lives in the session.
     */
    NBTBase serializeMap(MapData saved);
}
