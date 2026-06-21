// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * Whether a download starts a fresh archive folder or resumes an existing one, the two surfaces of the engine:
 * {@link #NEW} writes to a disambiguated new folder; {@link #RESUME} re-runs into an existing wdl-managed folder and
 * adds to it (new chunks, more entities, containers, maps, and dimensions) without clobbering prior content.
 * Version-agnostic core: imports no {@code net.minecraft.*} type, so it crosses the capture seam on every band.
 */
public enum DownloadMode {
    NEW,
    RESUME
}
