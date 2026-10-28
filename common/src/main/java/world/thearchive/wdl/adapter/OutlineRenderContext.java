// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

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
