// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.culling.Culler;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class OutlineRenderContext {
    private final BufferBuilder lines;
    private final @Nullable Culler frustum;
    private final Vec3 cameraPos;
    private final float lineWidth;

    public OutlineRenderContext(BufferBuilder lines, @Nullable Culler frustum, Vec3 cameraPos, float lineWidth) {
        this.lines = lines;
        this.frustum = frustum;
        this.cameraPos = cameraPos;
        this.lineWidth = lineWidth;
    }

    public BufferBuilder lines() {
        return lines;
    }

    public @Nullable Culler frustum() {
        return frustum;
    }

    public Vec3 cameraPos() {
        return cameraPos;
    }

    public float lineWidth() {
        return lineWidth;
    }
}
