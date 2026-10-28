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
 * The lines batch is flushed explicitly.
 */
final class NeoForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                draw(event);
            }
        });
    }

    private void draw(RenderLevelStageEvent event) {
        MultiBufferSource.BufferSource consumers = Minecraft.getInstance().renderBuffers().bufferSource();
        WdlOutlineRenderer.render(event.getPoseStack(), consumers, event.getFrustum(), rimRenderer);
        consumers.endBatch(RenderType.lines());
    }
}
