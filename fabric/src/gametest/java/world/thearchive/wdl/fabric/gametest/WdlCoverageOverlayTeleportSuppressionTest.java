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

/**
 * Anomaly-window suppression of a teleport's removal flood: a player teleport arms the sampler's anomaly window, and
 * every entity removal the teleport tees while the window holds must be dropped, never fed to the estimator. On a
 * reduced {@code entity-broadcast-range-percentage} server (30 percent, scaled range 48 blocks) an item frame 40 blocks
 * away arrives in range and calibrates a radius-2 baseline. Teleporting the player 50 blocks the opposite way pushes
 * the frame out of range, and the server's unpair tees a RemoveEntities the instant after the teleport's PlayerPosition
 * packet. That removal's honest distance is about 90 blocks, 74 post-haircut ({@code SendRangeSampler.HAIRCUT_BLOCKS});
 * if it were sampled it would ratchet the radius from 2 to 4.
 *
 * <p>The two suppression guards are pinned as distinct effects. The 74-block removal sits strictly inside the
 * {@code renderDistance} times 16, 80-block, plausibility bound, so the bound does not reject it and cannot stand in
 * for the window: the only thing that keeps the radius at 2 is the anomaly window the teleport's PlayerPosition packet
 * arms ({@code SendRangeSampler.onAnomalyPacket}). The flat fixture world spawns no mobs and holds no other decoration,
 * so the summoned frame is the sole calibration source and nothing else pre-calibrates the dimension.
 *
 * <p>The leak arithmetic rests on one timing assumption: the unpair's RemoveEntities trails the teleport's
 * PlayerPosition by about one server tick, because {@code runCommand} runs between ticks, so the removal is teed after
 * the client has applied the teleport and armed the window. Were both ever to land in a single flush with the removal
 * ordered first, this test would lose its failure power silently, not go false-red.
 */
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

            // A frame 40 blocks east, within the scaled 48-block send range, hung on a constructed wall block
            // (frames need a vertical face) and anchored on the player's block position as the suite's range
            // tests do, so its arrival commits a deterministic 40-block sample: radius 2 (arrivals take no
            // haircut). Summoned after the start window expired, so its arrival measures the scaled truth
            // instead of being suppressed.
            int frameX = playerBlockPos.getX() + 40;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            // Wait until the client actually received the frame, so the baseline reads with the sample present
            // (synchronization only; the assertions read the estimator, not the frame).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));
            driver.tick(10); // margin for the arrival sample to commit before the baseline read

            Check.that(driver.rangeRadiusChunks(dimension, 5) == 2,
                    "the in-range arrival must calibrate the baseline radius to 2, got "
                            + driver.rangeRadiusChunks(dimension, 5));

            // Teleport the player 50 blocks west, opposite the frame: the frame leaves the scaled range and the
            // server tees its RemoveEntities in-stream, after the teleport's window-arming PlayerPosition. The
            // player-to-frame distance is now about 90 blocks, 74 post-haircut, strictly inside the 80-block
            // plausibility bound, so an unsuppressed removal would ratchet the radius from 2 to 4 (74 >> 4). The
            // teleport keeps the same y and z as the frame, so the whole separation is on the x axis.
            fixture.server().runCommand(
                    "tp @p " + (playerBlockPos.getX() - 50) + " " + frameY + " " + frameZ);
            // Wait until the frame has left the client, which confirms the unpair's RemoveEntities was teed and
            // its removal sample ran (suppressed) before the radius read below.
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(160)).stream()
                    .noneMatch(ItemFrame.class::isInstance));
            driver.tick(20); // let the teed removal settle before the radius read

            Check.that(driver.rangeRadiusChunks(dimension, 5) == 2,
                    "the teleport-armed window must suppress the removal flood and hold the radius at 2, got "
                            + driver.rangeRadiusChunks(dimension, 5));

            driver.stopAndAwaitSave();
        }
    }
}
