// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import world.thearchive.wdl.core.MarkerHue;
import world.thearchive.wdl.core.RimFace;

/**
 * One container's rim in the outline draw-set: the hue its classification earned, the exposed {@link RimFace} it draws
 * on, the world-space shape box the rim's face plane sits on (the container's shape bounds, merged for a double chest,
 * or the live bounding box for a vehicle), the block-cell box the rim's in-plane rectangle spans (the full unit cell,
 * or two for a double chest, so a recessed model like a chest is framed at block extent), and the block pos keys of its
 * cells, which the tracker seals against each tick to recompute that face. An empty cells array marks an entity-borne
 * container, whose rim sits on its box top face (it has no block neighbors to seal), and whose cell box is that box.
 *
 * <p>The geometry (both boxes and the cells) is immutable, but the hue and face are settable so a cached
 * block-container rim can be reused across ticks: the tracker caches the rim's geometry per chunk and restamps its hue
 * from the live classification and its face from the seal test each tick. Touched only on the client thread.
 */
public final class OutlineRim {
    private MarkerHue hue;
    private RimFace face = RimFace.NONE;
    private final AxisAlignedBB box;
    private final AxisAlignedBB cellBox;
    private final long[] cells;

    OutlineRim(MarkerHue hue, AxisAlignedBB box, long[] cells) {
        this.hue = hue;
        this.box = box;
        this.cellBox = cellBoxOf(box, cells);
        this.cells = cells;
    }

    /**
     * The block-cell box: the union of unit cubes over the rim's cells (one cell, or two for a double chest), or the
     * passed box itself when there are no cells (an entity rim, whose cell box is its bounding box).
     */
    private static AxisAlignedBB cellBoxOf(AxisAlignedBB box, long[] cells) {
        if (cells.length == 0) {
            return box;
        }
        BlockPos first = BlockPos.fromLong(cells[0]);
        double minX = first.getX();
        double minY = first.getY();
        double minZ = first.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        for (int i = 1; i < cells.length; i++) {
            BlockPos cell = BlockPos.fromLong(cells[i]);
            double x = cell.getX();
            double y = cell.getY();
            double z = cell.getZ();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1.0);
            maxY = Math.max(maxY, y + 1.0);
            maxZ = Math.max(maxZ, z + 1.0);
        }
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Restamp the hue from this tick's classification (a cached rim is reused across ticks). */
    void hue(MarkerHue hue) {
        this.hue = hue;
    }

    public MarkerHue hue() {
        return hue;
    }

    /** Restamp the exposed face from this tick's seal test (a cached rim is reused across ticks). */
    void face(RimFace face) {
        this.face = face;
    }

    /** The exposed face the rim draws on, restamped each tick, or {@link RimFace#NONE} when fully enclosed. */
    public RimFace face() {
        return face;
    }

    public AxisAlignedBB box() {
        return box;
    }

    /** The block-cell box the rim's in-plane rectangle spans (full cell extent), as computed at cache time. */
    public AxisAlignedBB cellBox() {
        return cellBox;
    }

    /** The block pos keys of the container's one or two cells, or an empty array for an entity-borne rim. */
    long[] cells() {
        return cells;
    }

    /** Whether this rim is entity-borne (an empty cells array) rather than a block container's. */
    public boolean isEntityRim() {
        return cells.length == 0;
    }
}
