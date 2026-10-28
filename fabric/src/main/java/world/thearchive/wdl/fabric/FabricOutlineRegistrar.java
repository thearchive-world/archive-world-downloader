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
