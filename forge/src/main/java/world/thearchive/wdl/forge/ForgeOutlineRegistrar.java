// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Forge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It draws at the end of level
 * rendering, mixin-free, on the game event bus. Forge 28's RenderWorldLastEvent exposes neither a frustum nor a matrix
 * stack, so the shared renderer draws every section against the ambient pre-blaze3d model-view and lets the GPU clip.
 * The shared cull owns the Tesselator buffer begin and flush at this band, so nothing is flushed here.
 */
final class ForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        MinecraftForge.EVENT_BUS.addListener(
                (RenderWorldLastEvent event) -> WdlOutlineRenderer.render(null, rimRenderer));
    }
}
