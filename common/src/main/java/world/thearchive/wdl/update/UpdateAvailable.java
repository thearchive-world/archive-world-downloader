// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

/**
 * One positive check outcome, built once at publication with both versions already display-cleaned, so every surface
 * renders running to latest without re-deriving anything.
 */
public record UpdateAvailable(String runningDisplay, String latestDisplay) {}
