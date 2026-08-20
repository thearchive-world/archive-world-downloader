// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Forge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It draws at the end of
 * level rendering, mixin-free, on the game event bus. Forge 31's RenderWorldLastEvent exposes no frustum, so the
 * shared renderer's null-frustum path draws every section and lets the GPU clip. The lines batch is flushed
 * explicitly, since nothing after this event flushes it.
 */
final class ForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        MinecraftForge.EVENT_BUS.addListener((RenderWorldLastEvent event) -> draw(event));
    }

    private void draw(RenderWorldLastEvent event) {
        MultiBufferSource.BufferSource consumers = Minecraft.getInstance().renderBuffers().bufferSource();
        // Forge 31's RenderWorldLastEvent has no frustum, so the null path draws every section and lets the GPU clip.
        WdlOutlineRenderer.render(event.getMatrixStack(), consumers, null, rimRenderer);
        consumers.endBatch(RenderType.lines());
    }
}
