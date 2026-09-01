// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * The per-frame inputs the outline cull hands the injected renderer: the line buffer to write edges into, the live view
 * frustum for the section cull, the camera position so the draw is camera-relative, and the effective rim line width.
 * This pre-blaze3d band has no pose stack or buffer source: the buffer is the shared Tesselator's VertexBuffer, begun
 * on the POSITION_COLOR line format by the cull before any rim is drawn and flushed after, and the line width is
 * applied once as legacy GL state rather than carried per vertex. The cull reads the band-stable members and passes the
 * whole context to the injected {@link RimRenderer}, which owns the band-and-loader-varying draw.
 *
 * @param frustum   the live view frustum for the section cull, or null on a loader whose render event exposes none
 *                  (this band's Forge render event), where every section is drawn and off-screen geometry is clipped by
 *                  the GPU rather than skipped before build
 * @param lineWidth the effective rim line width in GL pixels, the config scale applied once as legacy line-width state
 *                  before the draw
 */
public final class OutlineRenderContext {
    private final VertexBuffer lines;
    private final @Nullable ICamera frustum;
    private final Vec3d cameraPos;
    private final float lineWidth;

    public OutlineRenderContext(VertexBuffer lines, @Nullable ICamera frustum, Vec3d cameraPos, float lineWidth) {
        this.lines = lines;
        this.frustum = frustum;
        this.cameraPos = cameraPos;
        this.lineWidth = lineWidth;
    }

    public VertexBuffer lines() {
        return lines;
    }

    public @Nullable ICamera frustum() {
        return frustum;
    }

    public Vec3d cameraPos() {
        return cameraPos;
    }

    public float lineWidth() {
        return lineWidth;
    }
}
