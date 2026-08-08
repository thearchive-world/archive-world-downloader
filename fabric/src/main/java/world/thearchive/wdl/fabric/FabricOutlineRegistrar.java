// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Fabric half of the outline render seam. On the collect-submits event it records the outline into the frame's
 * submit-node collector, handing the band-agnostic {@link WdlOutlineRenderer} the captured pose and lines buffer and
 * the band {@link RimRenderer} it constructs. 26.x fabric-api's render event no longer exposes the view frustum, so the
 * draw passes a null frustum and the band-agnostic cull draws every section rather than rejecting the off-screen ones
 * before build.
 */
final class FabricOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(this::submit);
    }

    private void submit(LevelRenderContext context) {
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lines(),
                (pose, buffer) -> WdlOutlineRenderer.render(pose, buffer, null, rimRenderer));
    }
}
