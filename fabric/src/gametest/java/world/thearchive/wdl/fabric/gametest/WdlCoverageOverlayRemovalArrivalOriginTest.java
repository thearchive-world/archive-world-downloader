// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayRemovalArrivalOriginTest implements FabricClientGameTest {
    private static final int WALK_TIMEOUT_TICKS = 600;
    /** Release the forward key this far from the frame: past the scaled 48-block range plus margin. */
    private static final double RELEASE_DISTANCE_BLOCKS = 60.0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-removal-arrival-origin", "wdl-removal-arrival-origin",
                            DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out BEFORE the summon, so the
            // frame is registered by its arrival through the tee, the origin under test, and its arrival
            // sample commits (about 20 blocks, radius 1, below the bracket the removal must reach).
            driver.tickUntilWindowExpired();

            // One frame 20 blocks west, hung on a constructed wall block, anchored on the player's block position.
            int frameX = playerBlockPos.getX() - 20;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));

            walkPastRange(context, driver, frameX, frameZ);

            int radius = driver.rangeRadiusChunks(dimension, 5);
            Check.that(radius >= 2 && radius <= 3,
                    "the walk-away removal must calibrate the radius into [2, 3], got " + radius);

            driver.stopAndAwaitSave();
        }
    }

    private static void walkPastRange(ClientGameTestContext context, CaptureDriver driver, int frameX, int frameZ) {
        // Input locomotion, never a player teleport: a /tp arms the sampler's anomaly window by design and would
        // suppress the removal sample. Plain walking covers about 0.22 blocks a tick, safely under the
        // SendRangeSampler.FAST_TICK_BLOCKS re-arm threshold.
        context.runOnClient(client -> {
            client.player.setYRot(-90.0f); // yaw -90 faces plus x (east), directly away from the frame
            client.player.setXRot(0.0f);
        });
        // An open screen suppresses movement input, so dismiss it before driving the forward key or the walk never
        // starts.
        context.runOnClient(client -> client.setScreen(null));
        context.getInput().holdKey(options -> options.keyUp);
        boolean pastRange = false;
        for (int walkTick = 0; walkTick < WALK_TIMEOUT_TICKS && !pastRange; walkTick++) {
            driver.tick(1); // keep pumping capture ticks mid-walk, as the production tick hook does
            Vec3 playerPos = context.computeOnClient(client -> client.player.position());
            double dx = playerPos.x - frameX;
            double dz = playerPos.z - frameZ;
            pastRange = dx * dx + dz * dz >= RELEASE_DISTANCE_BLOCKS * RELEASE_DISTANCE_BLOCKS;
        }
        context.getInput().releaseKey(options -> options.keyUp);
        Check.that(pastRange, "the player never walked " + (int) RELEASE_DISTANCE_BLOCKS
                + " blocks from the frame within " + WALK_TIMEOUT_TICKS + " ticks");
        driver.tick(10);
    }
}
