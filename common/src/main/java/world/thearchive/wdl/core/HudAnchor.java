// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * Where the capture HUD anchors on screen: the eight corner and edge positions of a three-by-three grid, excluding the
 * bottom-center (which the hotbar occupies). The default is a top corner, off the hotbar and health cluster and clear
 * of the top-center boss-bar stack the overlay does not reflow around. The overlay resolves the pixel position from the
 * anchor plus the configured offset, clamped to the screen.
 */
public enum HudAnchor {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    MIDDLE_LEFT,
    MIDDLE_CENTER,
    MIDDLE_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}
