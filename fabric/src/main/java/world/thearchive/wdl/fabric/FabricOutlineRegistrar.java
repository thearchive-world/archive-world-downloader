// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Fabric half of the outline render seam. It draws on the post-terrain debug-render event, mixin-free, handing the
 * band-agnostic {@link WdlOutlineRenderer} the render context's pose, buffers and view frustum and the band
 * {@link RimRenderer} it constructs. The frustum is read straight off the context, which carries it from AFTER_SETUP
 * onward, so the draw needs no per-frame stash at this band.
 */
final class FabricOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::draw);
    }

    private void draw(WorldRenderContext context) {
        WdlOutlineRenderer.render(context.matrixStack(), context.consumers(), context.frustum(), rimRenderer);
    }
}
