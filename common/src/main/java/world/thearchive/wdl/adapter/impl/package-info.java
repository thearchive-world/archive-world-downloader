// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in gradle.properties;
 * this branch targets 26.3). The package is role-named, not version-named, because a branch only ever carries one plug,
 * so the band it serves is recorded here rather than in the path.
 *
 * <p>This plug serves the 26.x W4 save shape (floor {@link VersionAdapterImpl#BAND_FLOOR}, validated at 26.3), the same
 * plug seams as 26.1 on every save axis; the floor is 26.3 rather than 26.1 because the plug also binds the 26.3 chunk
 * constructor, registry layer and worldgen settings. Minecraft ships unobfuscated.
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
