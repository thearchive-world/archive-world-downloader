// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in
 * {@code gradle.properties}; this branch targets 1.12.2). The package is role-named, not version-named, because a
 * branch only ever carries one plug, so the band it serves is recorded here rather than in the path.
 *
 * <p>This plug serves the pre-Flattening classic-MCP save shape below the 1.13 cut where Mojang mappings begin, so the
 * chunk write is this band's own numeric Blocks/Data/Add section encoding rather than {@code ChunkSerializer.write} or
 * the {@code SerializableChunkData} record (floor {@link VersionAdapterImpl#BAND_FLOOR}, which this branch reaches at
 * 1.12.2).
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
