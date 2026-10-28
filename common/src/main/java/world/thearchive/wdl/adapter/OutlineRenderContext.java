// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record OutlineRenderContext(PoseStack pose, MultiBufferSource consumers, @Nullable Frustum frustum,
        Vec3 cameraPos,
        float lineWidth) {}
