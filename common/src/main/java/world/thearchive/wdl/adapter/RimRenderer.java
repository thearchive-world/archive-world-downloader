// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.world.phys.AABB;

import world.thearchive.wdl.core.RimFace;

/**
 * The per-band, per-loader primitive that draws one container's rim: the four edges of the selected exposed
 * {@code face}, in {@code colorArgb}, into the sink the context carries. The rectangle's in-plane extent comes from
 * {@code cellBox} (the full block cell) while its face plane sits on {@code shapeBox} (the model surface), so a
 * recessed model is framed at block extent without disturbing a full-cube one. The registrar that knows both band and
 * loader constructs the right implementation and injects it, rather than resolving it through the
 * {@link VersionAdapter}, because the draw call goes loader-varying at later bands (the NeoForge submit-node draw
 * migration to {@code SubmitCustomGeometryEvent}, a 26.1 event that 26.2 forces) while the cull that calls it stays
 * loader-agnostic.
 */
public interface RimRenderer {
    /**
     * Draw the four edges of {@code face}, in-plane on {@code cellBox} and planed on {@code shapeBox}; a
     * {@link RimFace#NONE} face draws nothing.
     */
    void drawRim(OutlineRenderContext context, AABB cellBox, AABB shapeBox, RimFace face, int colorArgb);
}
