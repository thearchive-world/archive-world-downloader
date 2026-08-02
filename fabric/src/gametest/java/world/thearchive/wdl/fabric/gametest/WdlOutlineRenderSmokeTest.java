// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.core.RimFace;

/**
 * The render-draw smoke for the outline: the capture gametests record on their own controller, so the production render
 * (which gates on the static controller being recording) never reaches the per-band rim draw in those runs. This test
 * exercises {@link RimRendererImpl} directly in a real render frame, so a draw-side crash (a missing vertex element, a
 * bad pipeline) fails here instead of only in a live client run. It draws the four edges of one face through a real
 * {@code lines()} buffer; a frame that survives means the vertex format was written completely. The visual correctness
 * of the rim is not exercised headless.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlOutlineRenderSmokeTest implements FabricClientGameTest {
    private static volatile boolean drawTestRim;

    @Override
    public void runTest(ClientGameTestContext context) {
        RimRenderer rimRenderer = new RimRendererImpl();
        // Registered once, gated by the armed flag, so it draws only during this test and no-ops in every other.
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(worldContext -> {
            Minecraft client = Minecraft.getInstance();
            if (!drawTestRim || client.level == null || client.player == null) {
                return;
            }
            OutlineRenderContext renderContext = new OutlineRenderContext(worldContext.matrices(),
                    worldContext.consumers(), new Frustum(new Matrix4f(), new Matrix4f()),
                    client.gameRenderer.getMainCamera().position(), 2.5f);
            double x = client.player.getX();
            double y = client.player.getY();
            double z = client.player.getZ();
            AABB box = new AABB(x - 1.0, y, z - 1.0, x, y + 1.0, z);
            rimRenderer.drawRim(renderContext, box, box, RimFace.TOP, 0xFFFF0000);
        });

        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            drawTestRim = true;
            for (int frame = 0; frame < 10; frame++) {
                context.waitTick(); // each rendered frame fires the draw; a vertex-format crash kills it here
            }
            drawTestRim = false;
            Check.that(context.computeOnClient(client -> client.level != null),
                    "the client died while drawing the rim through RimRendererImpl");
        }
    }
}
