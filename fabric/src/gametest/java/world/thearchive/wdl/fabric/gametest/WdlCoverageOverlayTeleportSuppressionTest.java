// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayTeleportSuppressionTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-teleport-suppression", "wdl-teleport-suppression", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so the arrival below commits.
            driver.tickUntilWindowExpired();

            // A frame 40 blocks east, within the scaled 48-block send range, so its arrival commits radius 2 (arrivals
            // take no haircut).
            int frameX = playerBlockPos.getX() + 40;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));
            driver.tick(10);

            Check.that(driver.rangeRadiusChunks(dimension, 5) == 2,
                    "the in-range arrival must calibrate the baseline radius to 2, got "
                            + driver.rangeRadiusChunks(dimension, 5));

            fixture.server().runCommand(
                    "tp @p " + (playerBlockPos.getX() - 50) + " " + frameY + " " + frameZ);
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(160)).stream()
                    .noneMatch(ItemFrame.class::isInstance));
            driver.tick(20);

            Check.that(driver.rangeRadiusChunks(dimension, 5) == 2,
                    "the teleport-armed window must suppress the removal flood and hold the radius at 2, got "
                            + driver.rangeRadiusChunks(dimension, 5));

            driver.stopAndAwaitSave();
        }
    }
}
