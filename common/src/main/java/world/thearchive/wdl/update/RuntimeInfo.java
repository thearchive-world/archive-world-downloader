// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import world.thearchive.wdl.core.update.SemVer;

/**
 * The immutable boundary inputs of one update check, built once at launch: the running loader already normalized to the
 * index's lowercase token, the running MC version, the running version both parsed (for comparison) and raw (for
 * display cleaning), and the index project id.
 */
public record RuntimeInfo(String loaderId, String mcVersion, SemVer running, String runningRaw,
        String modrinthId) {}
