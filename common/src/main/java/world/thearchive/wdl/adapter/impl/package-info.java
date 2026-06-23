// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in
 * {@code gradle.properties}; this branch targets 1.21.11). The package is role-named, not version-named, because a
 * branch only ever carries one plug, so the band it serves is recorded here rather than in the path.
 *
 * <p>This plug serves the modern post-1.21.10 save shape (floor {@link VersionAdapterImpl#BAND_FLOOR}, validated at
 * 1.21.11). The vanilla save type each axis binds: {@link ChunkCodecImpl} binds {@code SerializableChunkData} built
 * through {@code PalettedContainerFactory}; {@link EntitySinkImpl}, {@link ContainerSinkImpl}, {@link LecternSinkImpl},
 * and {@link PlayerSinkImpl} bind the {@code ValueOutput} layer via {@code TagValueOutput}; {@link LevelDataWriterImpl}
 * binds {@code PrimaryLevelData} with the {@code saveDataTag(RegistryAccess, WorldData[, Player])} form;
 * {@link WorldPathsImpl} binds {@code DimensionType.getStorageFolder} and {@code RegionStorageInfo}.
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
