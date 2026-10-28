// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayBoostedPairingTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-boosted-pairing", "wdl-boosted-pairing", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so an honest arrival would commit,
            // proving the drops below, not the window, are what keep the dimension uncalibrated.
            driver.tickUntilWindowExpired();

            // Anchored on the player's block position at a deterministic 70 blocks east; the boosted range 76 pairs it,
            // the un-boosted range 48 would not.
            int standX = playerBlockPos.getX() + 70;
            int standZ = playerBlockPos.getZ();
            int standY = playerBlockPos.getY();
            fixture.server().runCommand("summon minecraft:armor_stand " + standX + " " + standY + " " + standZ
                    + " {Passengers:[{id:\"minecraft:end_crystal\"}]}");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(96)).stream()
                    .anyMatch(ArmorStand.class::isInstance));
            driver.tick(20);

            Check.that(!driver.isCalibrated(dimension),
                    "the boosted pair's arrival must stay dropped, leaving the dimension uncalibrated");

            fixture.server().runCommand("kill @e[type=end_crystal]");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(96)).stream()
                    .noneMatch(ArmorStand.class::isInstance));
            driver.tick(60);

            Check.that(!driver.isCalibrated(dimension),
                    "the un-boosted stand's ridden removal must stay dropped, leaving the dimension uncalibrated");

            driver.stopAndAwaitSave();
        }
    }
}
