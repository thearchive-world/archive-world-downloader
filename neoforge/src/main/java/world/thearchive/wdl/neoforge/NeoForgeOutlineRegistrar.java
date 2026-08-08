// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The NeoForge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It stashes the live view
 * frustum on the level-render-state extraction event and records the outline into the frame's submit-node collector on
 * the submit-custom-geometry event, both mixin-free on the game event bus. A missing stash skips the draw rather than
 * dereferencing null. It records into the collector rather than drawing immediately because at 26.2 the reduced
 * main-pass stage events fire with an identity pose and no shared buffer flush, so the old
 * {@code AfterTranslucentBlocks} immediate-mode draw into a shared buffer source no longer draws.
 */
final class NeoForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();
    private @Nullable Frustum stashedFrustum;

    void register() {
        NeoForge.EVENT_BUS.addListener((ExtractLevelRenderStateEvent event) -> stashedFrustum = event.getFrustum());
        NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent event) -> submit(event));
    }

    private void submit(SubmitCustomGeometryEvent event) {
        Frustum frustum = stashedFrustum;
        if (frustum == null) {
            return;
        }
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.lines(),
                (pose, buffer) -> WdlOutlineRenderer.render(pose, buffer, frustum, rimRenderer));
    }
}
