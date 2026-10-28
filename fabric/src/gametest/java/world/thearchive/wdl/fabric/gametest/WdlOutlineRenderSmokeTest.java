// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

import world.thearchive.wdl.adapter.OutlineRenderContext;
import world.thearchive.wdl.adapter.RimRenderer;
import world.thearchive.wdl.adapter.impl.RimRendererImpl;
import world.thearchive.wdl.core.RimFace;

@SuppressWarnings("UnstableApiUsage")
public class WdlOutlineRenderSmokeTest implements FabricClientGameTest {
    private static volatile boolean drawTestRim;

    @Override
    public void runTest(ClientGameTestContext context) {
        RimRenderer rimRenderer = new RimRendererImpl();
        LevelRenderEvents.BEFORE_GIZMOS.register(worldContext -> {
            Minecraft client = Minecraft.getInstance();
            if (!drawTestRim || client.level == null || client.player == null) {
                return;
            }
            OutlineRenderContext renderContext = new OutlineRenderContext(worldContext.poseStack(),
                    worldContext.bufferSource(), null,
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
                context.waitTick();
            }
            drawTestRim = false;
            Check.that(context.computeOnClient(client -> client.level != null),
                    "the client died while drawing the rim through RimRendererImpl");
        }
    }
}
