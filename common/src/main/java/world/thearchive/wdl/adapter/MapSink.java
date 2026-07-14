// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Per-band map-serialize axis: encode a client {@link MapItemSavedData} into the inner {@code "data"} tag of a
 * {@code data/map_<id>.dat} save surface. The one genuinely per-band map piece: the serialize call is the 1.21.5
 * {@code SavedData} to {@code Codec} cut ({@code CODEC.encodeStart} on 1.21.11 vs {@code save(CompoundTag, Provider)}
 * on the &le;1.21.4 band), while the {@code {data, DataVersion}} gzip envelope and the {@code idcounts}
 * {@code {map: maxId}} shape are band-agnostic ({@link MapDataWriter}).
 *
 * <p>Mirrors {@link PlayerSink}'s lift. The single step is client-coupled (a live {@code MapItemSavedData} resolved
 * from the client's map store); the headless guard is the round-trip re-parse via the band's own
 * {@code MapItemSavedData.CODEC}.
 */
public interface MapSink {
    /**
     * Serialize {@code saved} into the inner {@code "data"} {@link Tag} a {@code data/map_<id>.dat} holds: the vanilla
     * {@code MapItemSavedData} persistence ({@code colors}/{@code dimension}/{@code scale}/ {@code locked}/...), via
     * the band's own codec. Lock-agnostic: it encodes whatever map the session hands it, and the auto-lock decision
     * lives in the session.
     */
    Tag serializeMap(MapItemSavedData saved, RegistryAccess registries);
}
