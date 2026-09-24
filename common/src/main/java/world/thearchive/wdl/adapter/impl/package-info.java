// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

/**
 * The version adapter plug for this branch: the concrete {@link world.thearchive.wdl.adapter.VersionAdapter} binding
 * for the MC version this branch targets (the authoritative value is {@code minecraft_version} in gradle.properties).
 * The package is role-named, not version-named, because a branch only ever carries one plug.
 *
 * <p>This plug serves the E2a save shape below the 1.21.2 chunk-serialization cut (floor
 * {@link VersionAdapterImpl#BAND_FLOOR}).
 */
@NullMarked
package world.thearchive.wdl.adapter.impl;

import org.jspecify.annotations.NullMarked;
