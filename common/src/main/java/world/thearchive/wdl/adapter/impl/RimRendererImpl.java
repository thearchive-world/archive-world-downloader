// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.core.RimFace;

/**
 * The rim primitive on this pre-blaze3d band: an explicit four-edge draw of the selected face as GL lines,
 * camera-relative. The edges are written straight to the shared line buffer the cull begins on the POSITION_COLOR
 * format, so a vertex carries position and color only; there is no pose to transform by, because the ambient model-view
 * already holds the camera orientation and the draw subtracts the camera position to reach that view space. The
 * rectangle's two in-plane axes span the block cell and its normal axis planes onto the model shape, lifted a small
 * standoff along the face normal, so the rim frames a recessed model (a chest) at block extent yet never z-fights a
 * flush one. We draw the edges explicitly rather than feed a flat {@code VoxelShape} to a shape renderer, which risks
 * degenerate zero-length edges. The line width is the global GL state the cull sets, not a per-vertex element.
 */
public final class RimRendererImpl implements RimRenderer {
    // A small outward lift of the drawn face along its normal, so the rim clears the model surface it planes
    // onto and does not z-fight it. Raise it if a rim shimmers on a band.
    private static final double SURFACE_STANDOFF = 0.02;

    @Override
    public void drawRim(OutlineRenderContext context, AABB cellBox, AABB shapeBox, RimFace face, int colorArgb) {
        if (face == RimFace.NONE) {
            return;
        }
        BufferBuilder lines = context.lines();
        Vec3 camera = context.cameraPos();
        // The two in-plane axes span the full block cell; the normal axis planes onto the model shape, lifted
        // outward by the standoff, so a recessed model is framed while a flush one is drawn on its own surface.
        float minX = (float) (cellBox.minX - camera.x);
        float minY = (float) (cellBox.minY - camera.y);
        float minZ = (float) (cellBox.minZ - camera.z);
        float maxX = (float) (cellBox.maxX - camera.x);
        float maxY = (float) (cellBox.maxY - camera.y);
        float maxZ = (float) (cellBox.maxZ - camera.z);
        switch (face) {
            case TOP: {
                float y = (float) (shapeBox.maxY + SURFACE_STANDOFF - camera.y);
                quad(lines, colorArgb, minX, y, minZ, maxX, y, minZ, maxX, y, maxZ, minX, y, maxZ);
                break;
            }
            case BOTTOM: {
                float y = (float) (shapeBox.minY - SURFACE_STANDOFF - camera.y);
                quad(lines, colorArgb, minX, y, minZ, maxX, y, minZ, maxX, y, maxZ, minX, y, maxZ);
                break;
            }
            case NORTH: {
                float z = (float) (shapeBox.minZ - SURFACE_STANDOFF - camera.z);
                quad(lines, colorArgb, minX, minY, z, maxX, minY, z, maxX, maxY, z, minX, maxY, z);
                break;
            }
            case SOUTH: {
                float z = (float) (shapeBox.maxZ + SURFACE_STANDOFF - camera.z);
                quad(lines, colorArgb, minX, minY, z, maxX, minY, z, maxX, maxY, z, minX, maxY, z);
                break;
            }
            case WEST: {
                float x = (float) (shapeBox.minX - SURFACE_STANDOFF - camera.x);
                quad(lines, colorArgb, x, minY, minZ, x, minY, maxZ, x, maxY, maxZ, x, maxY, minZ);
                break;
            }
            case EAST: {
                float x = (float) (shapeBox.maxX + SURFACE_STANDOFF - camera.x);
                quad(lines, colorArgb, x, minY, minZ, x, minY, maxZ, x, maxY, maxZ, x, maxY, minZ);
                break;
            }
            default:
                break;
        }
    }

    private void quad(BufferBuilder lines, int colorArgb, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz) {
        edge(lines, colorArgb, ax, ay, az, bx, by, bz);
        edge(lines, colorArgb, bx, by, bz, cx, cy, cz);
        edge(lines, colorArgb, cx, cy, cz, dx, dy, dz);
        edge(lines, colorArgb, dx, dy, dz, ax, ay, az);
    }

    private void edge(BufferBuilder lines, int colorArgb, float x1, float y1, float z1, float x2, float y2, float z2) {
        vertex(lines, x1, y1, z1, colorArgb);
        vertex(lines, x2, y2, z2, colorArgb);
    }

    private void vertex(BufferBuilder lines, float x, float y, float z, int colorArgb) {
        lines.vertex(x, y, z)
                .color((colorArgb >> 16) & 0xFF, (colorArgb >> 8) & 0xFF, colorArgb & 0xFF, (colorArgb >>> 24) & 0xFF)
                .endVertex();
    }
}
