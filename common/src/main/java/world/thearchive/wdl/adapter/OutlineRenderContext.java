// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class OutlineRenderContext {
    private final PoseStack pose;
    private final MultiBufferSource consumers;
    private final @Nullable Frustum frustum;
    private final Vec3 cameraPos;
    private final float lineWidth;

    public OutlineRenderContext(PoseStack pose, MultiBufferSource consumers, @Nullable Frustum frustum,
            Vec3 cameraPos, float lineWidth) {
        this.pose = pose;
        this.consumers = consumers;
        this.frustum = frustum;
        this.cameraPos = cameraPos;
        this.lineWidth = lineWidth;
    }

    public PoseStack pose() {
        return pose;
    }

    public MultiBufferSource consumers() {
        return consumers;
    }

    public @Nullable Frustum frustum() {
        return frustum;
    }

    public Vec3 cameraPos() {
        return cameraPos;
    }

    public float lineWidth() {
        return lineWidth;
    }
}
