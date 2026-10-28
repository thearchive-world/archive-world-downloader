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
