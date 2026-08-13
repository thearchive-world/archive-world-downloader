// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in
 * {@code gradle.properties}; this branch targets 1.21.8). The package is role-named, not version-named, because a
 * branch only ever carries one plug, so the band it serves is recorded here rather than in the path.
 *
 * <p>This plug serves 1.21.8's save shape: the pre-1.21.9 chunk codec (the biome {@code Registry} passed straight to
 * {@code SerializableChunkData}, before the {@code PalettedContainerFactory} cut) and the {@code ValueOutput} item
 * shape, but still the typed pre-1.21.11 game rules (floor {@link VersionAdapterImpl#BAND_FLOOR}, validated at 1.21.8).
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
