// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The face a container's rim sits on, or {@link #NONE} when the container is fully enclosed. The six real faces are
 * declared in the exposed-face preference order (top, then bottom, then the sides), so {@link #selectExposed} returns
 * the first open one. MC-free and Java-8-clean; the adapter maps each face to its block direction for the per-frame
 * neighbor read and the draw.
 */
public enum RimFace {
    TOP,
    BOTTOM,
    NORTH,
    SOUTH,
    WEST,
    EAST,
    NONE;

    /**
     * The face the rim sits on given each neighbor's collision-shape-sealed state, in the preference order top, bottom,
     * north, south, west, east; {@link #NONE} when every face is sealed. Each boolean is true when that face's neighbor
     * is a full collision block (the seal test lives in the adapter), supplied in that same order.
     */
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
