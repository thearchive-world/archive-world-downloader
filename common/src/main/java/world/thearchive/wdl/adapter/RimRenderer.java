// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.util.math.AxisAlignedBB;

import world.thearchive.wdl.core.RimFace;

/**
 * The per-band primitive that draws one container's rim: the four edges of the selected exposed {@code face}, in
 * {@code colorArgb}, into the sink the context carries. The rectangle's in-plane extent comes from {@code cellBox} (the
 * full block cell) while its face plane sits on {@code shapeBox} (the model surface), so a recessed model is framed at
 * block extent without disturbing a full-cube one. The registrar constructs the implementation and injects it.
 */
public interface RimRenderer {
    /**
     * Draw the four edges of {@code face}, in-plane on {@code cellBox} and planed on {@code shapeBox}; a
     * {@link RimFace#NONE} face draws nothing.
     */
    void drawRim(OutlineRenderContext context, AxisAlignedBB cellBox, AxisAlignedBB shapeBox, RimFace face,
            int colorArgb);
}
