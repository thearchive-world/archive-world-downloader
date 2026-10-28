// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import world.thearchive.wdl.core.MarkerHue;
import world.thearchive.wdl.core.RimFace;

/**
 * Touched only on the client thread.
 */
public final class OutlineRim {
    private MarkerHue hue;
    private RimFace face = RimFace.NONE;
    private final AABB box;
    private final AABB cellBox;
    private final long[] cells;

    OutlineRim(MarkerHue hue, AABB box, long[] cells) {
        this.hue = hue;
        this.box = box;
        this.cellBox = cellBoxOf(box, cells);
        this.cells = cells;
    }

    /**
     * The block-cell box: the union of unit cubes over the rim's cells (one cell, or two for a double chest), so a
     * recessed model like a chest is framed at block extent, or the passed box itself when there are no cells.
     */
    private static AABB cellBoxOf(AABB box, long[] cells) {
        if (cells.length == 0) {
            return box;
        }
        double minX = BlockPos.getX(cells[0]);
        double minY = BlockPos.getY(cells[0]);
        double minZ = BlockPos.getZ(cells[0]);
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        for (int i = 1; i < cells.length; i++) {
            double x = BlockPos.getX(cells[i]);
            double y = BlockPos.getY(cells[i]);
            double z = BlockPos.getZ(cells[i]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1.0);
            maxY = Math.max(maxY, y + 1.0);
            maxZ = Math.max(maxZ, z + 1.0);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    void hue(MarkerHue hue) {
        this.hue = hue;
    }

    public MarkerHue hue() {
        return hue;
    }

    void face(RimFace face) {
        this.face = face;
    }

    /** The exposed face the rim draws on, or {@link RimFace#NONE} when fully enclosed. */
    public RimFace face() {
        return face;
    }

    public AABB box() {
        return box;
    }

    /** The box the rim's in-plane rectangle spans. */
    public AABB cellBox() {
        return cellBox;
    }

    long[] cells() {
        return cells;
    }

    public boolean isEntityRim() {
        return cells.length == 0;
    }
}
