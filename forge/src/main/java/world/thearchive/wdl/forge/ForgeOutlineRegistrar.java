// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.client.WdlOutlineRenderer;

/**
 * The Forge half of the outline render seam, the analog of {@code FabricOutlineRegistrar}. It draws at the end of level
 * rendering, mixin-free, on the game event bus. At this band {@code MinecraftForge.EVENT_BUS} is the pre-ModLauncher
 * {@code net.minecraftforge.fml.common.eventhandler.EventBus}, which dispatches to {@code @SubscribeEvent}-annotated
 * instance methods discovered by {@link MinecraftForge#EVENT_BUS}{@code .register(Object)} rather than to a lambda
 * (there is no functional {@code addListener} overload at this band), so registration listens on this instance itself.
 * RenderWorldLastEvent exposes neither a frustum nor a matrix stack, so the shared renderer draws every section
 * against the ambient pre-blaze3d model-view and lets the GPU clip. The shared cull owns the Tesselator buffer begin
 * and flush at this band, so nothing is flushed here.
 */
final class ForgeOutlineRegistrar {
    private final RimRenderer rimRenderer = new RimRendererImpl();

    void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        WdlOutlineRenderer.render(null, rimRenderer);
    }
}
