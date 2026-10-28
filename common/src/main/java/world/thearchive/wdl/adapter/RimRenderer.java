// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.phys.AABB;

import world.thearchive.wdl.core.RimFace;

public interface RimRenderer {
    /**
     * Draw the four edges of {@code face}, in-plane on {@code cellBox} and planed on {@code shapeBox}; a
     * {@link RimFace#NONE} face draws nothing.
     */
    void drawRim(OutlineRenderContext context, AABB cellBox, AABB shapeBox, RimFace face, int colorArgb);
}
