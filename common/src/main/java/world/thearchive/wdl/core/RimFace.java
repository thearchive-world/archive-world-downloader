// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public enum RimFace {
    TOP,
    BOTTOM,
    NORTH,
    SOUTH,
    WEST,
    EAST,
    NONE;

    public static RimFace selectExposed(boolean topSealed, boolean bottomSealed, boolean northSealed,
            boolean southSealed, boolean westSealed, boolean eastSealed) {
        if (!topSealed) {
            return TOP;
        }
        if (!bottomSealed) {
            return BOTTOM;
        }
        if (!northSealed) {
            return NORTH;
        }
        if (!southSealed) {
            return SOUTH;
        }
        if (!westSealed) {
            return WEST;
        }
        if (!eastSealed) {
            return EAST;
        }
        return NONE;
    }
}
