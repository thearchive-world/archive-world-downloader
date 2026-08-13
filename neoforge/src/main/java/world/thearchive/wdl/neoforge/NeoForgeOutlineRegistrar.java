// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The NeoForge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It draws after the
 * translucent-blocks stage, mixin-free, on the game event bus, reading the view frustum straight off the stage event.
 * The lines batch is flushed explicitly, since nothing after this stage flushes it.
 */
final class NeoForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> draw(event));
    }

    private void draw(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        MultiBufferSource.BufferSource consumers = Minecraft.getInstance().renderBuffers().bufferSource();
        WdlOutlineRenderer.render(event.getPoseStack(), consumers, event.getFrustum(), rimRenderer);
        consumers.endBatch(RenderType.lines());
    }
}
