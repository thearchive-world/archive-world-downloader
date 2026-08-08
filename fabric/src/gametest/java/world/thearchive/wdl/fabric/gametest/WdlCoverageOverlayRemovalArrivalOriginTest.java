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

/**
 * Removal calibration against an arrival-registered anchor: the item frame is summoned only after the sampler's start
 * window has expired, so the packet tee's arrival registration is the sole anchor (the chunk prime back-fills only
 * entities present when the download began). The arrival itself commits a roughly 20-block sample, radius 1, which
 * cannot reach the assertion bracket, so the [2, 3] assert still pins the removal resolving against that anchor: once
 * the walking player passes the scaled 48-block send range ({@code entity-broadcast-range-percentage} 30 of
 * {@code clientTrackingRange(10) * 16}) the server unpairs the frame and the teed RemoveEntities packet samples the
 * anchored distance minus the 16-block haircut ({@code SendRangeSampler.HAIRCUT_BLOCKS}). A sibling test pins the same
 * removal against a seed-registered anchor; the two registration origins are pinned separately so a broken resolution
 * in one cannot hide behind the other.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayRemovalArrivalOriginTest implements FabricClientGameTest {
    /** Upper bound on walk ticks before the run is declared stuck (plain walking covers ~0.22 blocks a tick). */
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

            // One frame 20 blocks west, hung on a constructed wall block (frames need a vertical face),
            // anchored on the player's block position as the suite's range tests do. The frame sits on the
            // far side of the walk on purpose: a hanging entity's spawn packet carries its BLOCK position
            // while the server range-checks the snapped entity position, up to a block closer to its
            // support; with the support between frame and player that corner bias points away from the
            // walking player, keeping the floored removal sample off the 16-block radius boundary.
            int frameX = playerBlockPos.getX() - 20;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            // Wait until the client actually received the frame, so the walk starts with the anchor
            // registered (synchronization only; the assertion reads the estimator, not the frame).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));

            walkPastRange(context, driver, frameX, frameZ);

            // Haircut-aware bracket: the server unpairs just past the scaled 48-block range and the removal
            // sample is the anchored distance minus the 16-block haircut, so a correct build floors to
            // radius 2, with 3 admitted because the unpair can trail the crossing by movement ticks and
            // sample a longer distance. The bracket's width is that prune lag, not haircut tolerance: an
            // un-haircut removal also lands inside the bracket, and the haircut arithmetic is pinned by the
            // sampler unit tests, not here. Exact equality would go red against a correct build under lag.
            int radius = driver.rangeRadiusChunks(dimension, 5);
            Check.that(radius >= 2 && radius <= 3,
                    "the walk-away removal must calibrate the radius into [2, 3], got " + radius);

            driver.stopAndAwaitSave();
        }
    }

    /**
     * Walk the player due east, straight away from the frame, on real forward-key input, pumping a capture tick every
     * walk tick; releases the key once past {@link #RELEASE_DISTANCE_BLOCKS} from the frame.
     */
    private static void walkPastRange(ClientGameTestContext context, CaptureDriver driver, int frameX, int frameZ) {
        // Input locomotion, never a player teleport: a /tp arms the sampler's anomaly window by design and
        // would suppress the removal sample. Plain walking covers about 0.22 blocks a tick, safely under
        // the SendRangeSampler.FAST_TICK_BLOCKS re-arm threshold (no sprinting, for the same headroom).
        context.runOnClient(client -> {
            client.player.setYRot(-90.0f); // yaw -90 faces plus x (east), directly away from the frame
            client.player.setXRot(0.0f);
        });
        // A reconnect earlier in a multi-scenario run can leave a confirm screen focused, and an open screen
        // suppresses movement input, so dismiss it before driving the forward key or the walk never starts.
        context.runOnClient(client -> client.gui.setScreen(null));
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
        driver.tick(10); // margin for the teed removal to land before the radius read
    }
}
