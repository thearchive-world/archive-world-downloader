// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.culling.Frustum;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Fabric half of the outline render seam. It stashes the live view frustum on the public pre-pass extraction event
 * and draws on the post-terrain debug-render event, both mixin-free, handing the band-agnostic
 * {@link WdlOutlineRenderer} a render context and the band {@link RimRenderer} it constructs. The frustum is a
 * per-frame stash because the draw event does not carry it; a missing stash (the first frame, or a frame with no
 * extraction) skips the draw rather than dereferencing null.
 */
final class FabricOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();
    private @Nullable Frustum stashedFrustum;

    void register() {
        WorldRenderEvents.END_EXTRACTION.register(context -> stashedFrustum = context.frustum());
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::draw);
    }

    private void draw(WorldRenderContext context) {
        Frustum frustum = stashedFrustum;
        if (frustum == null) {
            return;
        }
        WdlOutlineRenderer.render(context.matrices(), context.consumers(), frustum, rimRenderer);
    }
}
