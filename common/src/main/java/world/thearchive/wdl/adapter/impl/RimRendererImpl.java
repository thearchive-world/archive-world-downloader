// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.core.RimFace;

/**
 * The 1.18.2 rim primitive: an explicit four-edge draw of the selected face on the depth-tested
 * {@link RenderType#lines()} type, camera-relative. The rectangle's two in-plane axes come from the block cell and its
 * normal axis from the model shape, lifted a small standoff along the face normal, so the rim frames a recessed model
 * (a chest) at block extent yet never z-fights a flush one. Each edge carries its own direction as its normal, because
 * the lines shader thickens perpendicular to position plus normal, so a face-outward normal would collapse the edge to
 * zero width. We draw the edges explicitly rather than feed a flat {@code VoxelShape} to {@code renderShape}, which
 * risks degenerate zero-length edges.
 */
public final class RimRendererImpl implements RimRenderer {
    // A small outward lift of the drawn face along its normal, so the rim clears the model surface it planes
    // onto and does not z-fight it. Raise it if a rim shimmers on a band.
    private static final double SURFACE_STANDOFF = 0.02;

    // The transformed position and edge normal, reused so neither per-vertex write allocates on the render
    // path: vertex(Pose, ...) and normal(Pose, ...) each new a Vector3f per call, two per vertex, the
    // dominant outline allocation under a dense base, and the per-frame path must stay allocation-free.
    // We do both transforms into these fields and write with the no-pose overloads. Render thread only.
    private final Vector4f position = new Vector4f();
    private final Vector3f normal = new Vector3f();

    @Override
    public void drawRim(OutlineRenderContext context, AABB cellBox, AABB shapeBox, RimFace face, int colorArgb) {
        if (face == RimFace.NONE) {
            return;
        }
        VertexConsumer lines = context.consumers().getBuffer(RenderType.lines());
        PoseStack.Pose pose = context.pose().last();
        float width = context.lineWidth();
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
            case TOP -> {
                float y = (float) (shapeBox.maxY + SURFACE_STANDOFF - camera.y);
                quad(lines, pose, width, colorArgb, minX, y, minZ, maxX, y, minZ, maxX, y, maxZ, minX, y, maxZ);
            }
            case BOTTOM -> {
                float y = (float) (shapeBox.minY - SURFACE_STANDOFF - camera.y);
                quad(lines, pose, width, colorArgb, minX, y, minZ, maxX, y, minZ, maxX, y, maxZ, minX, y, maxZ);
            }
            case NORTH -> {
                float z = (float) (shapeBox.minZ - SURFACE_STANDOFF - camera.z);
                quad(lines, pose, width, colorArgb, minX, minY, z, maxX, minY, z, maxX, maxY, z, minX, maxY, z);
            }
            case SOUTH -> {
                float z = (float) (shapeBox.maxZ + SURFACE_STANDOFF - camera.z);
                quad(lines, pose, width, colorArgb, minX, minY, z, maxX, minY, z, maxX, maxY, z, minX, maxY, z);
            }
            case WEST -> {
                float x = (float) (shapeBox.minX - SURFACE_STANDOFF - camera.x);
                quad(lines, pose, width, colorArgb, x, minY, minZ, x, minY, maxZ, x, maxY, maxZ, x, maxY, minZ);
            }
            case EAST -> {
                float x = (float) (shapeBox.maxX + SURFACE_STANDOFF - camera.x);
                quad(lines, pose, width, colorArgb, x, minY, minZ, x, minY, maxZ, x, maxY, maxZ, x, maxY, minZ);
            }
            default -> {}
        }
    }

    private void quad(VertexConsumer lines, PoseStack.Pose pose, float width, int colorArgb, float ax, float ay,
            float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        edge(lines, pose, width, colorArgb, ax, ay, az, bx, by, bz);
        edge(lines, pose, width, colorArgb, bx, by, bz, cx, cy, cz);
        edge(lines, pose, width, colorArgb, cx, cy, cz, dx, dy, dz);
        edge(lines, pose, width, colorArgb, dx, dy, dz, ax, ay, az);
    }

    private void edge(VertexConsumer lines, PoseStack.Pose pose, float width, int colorArgb, float x1, float y1,
            float z1, float x2, float y2, float z2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0.0f) {
            dx /= length;
            dy /= length;
            dz /= length;
        }
        normal.set(dx, dy, dz);
        normal.transform(pose.normal()); // once per edge; both vertices share the edge direction
        vertex(lines, pose, width, x1, y1, z1, colorArgb);
        vertex(lines, pose, width, x2, y2, z2, colorArgb);
    }

    // The lines format is POSITION_COLOR_NORMAL on this band: it carries no per-vertex width element, so line
    // width is the global vanilla default and the context's resolved width is threaded here but not applied.
    private void vertex(VertexConsumer lines, PoseStack.Pose pose, float width, float x, float y, float z,
            int colorArgb) {
        position.set(x, y, z, 1.0F);
        position.transform(pose.pose());
        lines.vertex(position.x(), position.y(), position.z()).color(colorArgb)
                .normal(normal.x(), normal.y(), normal.z()).endVertex();
    }
}
