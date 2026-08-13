// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The NeoForge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It stashes the live view
 * frustum on the public level-render-state extraction event and draws after the translucent-blocks stage, both
 * mixin-free, on the game event bus. A missing stash skips the draw rather than dereferencing null. The lines batch is
 * flushed explicitly, since nothing after this stage flushes it.
 */
final class NeoForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();
    private @Nullable Frustum stashedFrustum;

    void register() {
        NeoForge.EVENT_BUS.addListener((ExtractLevelRenderStateEvent event) -> stashedFrustum = event.getFrustum());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> draw(event));
    }

    private void draw(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Frustum frustum = stashedFrustum;
        if (frustum == null) {
            return;
        }
        MultiBufferSource.BufferSource consumers = Minecraft.getInstance().renderBuffers().bufferSource();
        WdlOutlineRenderer.render(event.getPoseStack(), consumers, frustum, rimRenderer);
        consumers.endBatch(RenderType.lines());
    }
}
