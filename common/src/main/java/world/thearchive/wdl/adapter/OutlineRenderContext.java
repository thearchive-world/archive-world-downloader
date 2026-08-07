// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The per-frame inputs the per-loader registrar hands the outline renderer: the pose stack and vertex sink to draw
 * into, the live view frustum for the section cull, the camera position so the draw can be camera-relative, and the
 * effective rim line width. The width is resolved here rather than in the band-agnostic cull because its base
 * ({@code Window.getAppropriateLineWidth}) is band-specific and absent on some bands, while the config scale that
 * multiplies it is band-agnostic. The registrar builds it from its loader's render event; the band-agnostic cull reads
 * only the band-stable members and passes the whole context to the injected {@link RimRenderer}, which owns the
 * band-and-loader-varying draw.
 *
 * @param frustum   the live view frustum for the section cull, or null on a band whose loader render event no longer
 *                  exposes one (26.x fabric-api), where every section is drawn and off-screen geometry is clipped by
 *                  the GPU rather than skipped before build
 * @param lineWidth the effective rim line width in pixels: the config scale times the band's appropriate width
 */
public record OutlineRenderContext(PoseStack pose, MultiBufferSource consumers, @Nullable Frustum frustum,
        Vec3 cameraPos,
        float lineWidth) {}
