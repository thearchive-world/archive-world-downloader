// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The three-way outline classification: a container whose contents were captured this session draws no rim, one
 * recovered from a prior session draws the recovered hue, and an as-yet-unsaved one draws the unsaved hue. The hue
 * mapping (recovered and unsaved to a {@link MarkerHue}) is the consumer's, resolved from config; this enum carries
 * only the state.
 */
public enum OutlineClass {
    CAPTURED,
    RECOVERED,
    UNSAVED
}
