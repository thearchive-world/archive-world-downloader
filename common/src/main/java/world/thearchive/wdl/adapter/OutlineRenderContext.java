// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * @param lineWidth the effective rim line width in pixels: the config scale times the band's appropriate width
 */
public record OutlineRenderContext(PoseStack.Pose pose, VertexConsumer buffer, @Nullable Frustum frustum,
        Vec3 cameraPos,
        float lineWidth) {}
