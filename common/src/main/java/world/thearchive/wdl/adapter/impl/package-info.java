// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in
 * {@code gradle.properties}; this branch targets 1.21.1). The package is role-named, not version-named, because a
 * branch only ever carries one plug, so the band it serves is recorded here rather than in the path.
 *
 * <p>This plug serves the E2b save shape below the 1.21.2 chunk-serialization cut, where the chunk write is the static
 * {@code ChunkSerializer.write} rather than the {@code SerializableChunkData} record (floor
 * {@link VersionAdapterImpl#BAND_FLOOR}, validated at 1.21.1).
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
