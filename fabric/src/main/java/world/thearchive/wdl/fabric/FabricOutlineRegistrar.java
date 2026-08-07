// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Fabric half of the outline render seam. It draws on the pre-gizmo render event, which carries the pose stack and
 * buffer source, handing the band-agnostic {@link WdlOutlineRenderer} a render context and the band {@link RimRenderer}
 * it constructs. 26.x fabric-api's render event no longer exposes the view frustum, so the draw passes a null frustum
 * and the band-agnostic cull draws every section rather than rejecting the off-screen ones before build.
 */
final class FabricOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(this::draw);
    }

    private void draw(LevelRenderContext context) {
        WdlOutlineRenderer.render(context.poseStack(), context.bufferSource(), null, rimRenderer);
    }
}
