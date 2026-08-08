// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * A respawn survives the sampler's book clear without losing the death flood it just fed. On a full-range server
 * (default 100 percent, so the binding range is the 80-block view-distance cap) an item frame 20 blocks away arrives
 * and calibrates radius 1. The player walks out to a stop inside the death-distance window and dies: removing the old
 * player during the respawn tees a RemoveEntities for every entity it was tracking, the frame included, and that
 * removal samples the walked-out distance minus the haircut ({@code SendRangeSampler.HAIRCUT_BLOCKS}). The subsequent
 * Respawn packet clears the sampler's position book ({@code SendRangeSampler.onRespawn}) but not the estimator's
 * running max, so the death flood's sample must survive the respawn.
 *
 * <p>The stop window is chosen so only the death flood can produce the asserted radius. The player releases the walk
 * key around 70 blocks from the frame: below 64 the flood would sample under 48 and floor to radius 2, a false red; at
 * 80 and beyond the frame prunes mid-walk and the honest walk-out removal, not the death flood, would yield the radius,
 * a false attribution. A stop near 70 samples about 54 after the haircut and floors to radius 3, clear of both
 * boundaries. Walking covers about 0.22 blocks a tick, under the {@code SendRangeSampler.FAST_TICK_BLOCKS} re-arm
 * threshold, so the walk itself never arms the window.
 *
 * <p>The flood fires at the respawn request, before the book clear, so the assertion is read only after the respawn
 * completes; reading between the kill and the respawn would be a misread of when the sample lands.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayRespawnFloodTest implements FabricClientGameTest {
    /** Upper bound on walk ticks before the run is declared stuck (plain walking covers ~0.22 blocks a tick). */
    private static final int WALK_TIMEOUT_TICKS = 600;
    /** Release the forward key at this distance from the frame: mid death-distance window, clear of 64 and 80. */
    private static final double STOP_DISTANCE_BLOCKS = 70.0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-respawn-flood", "wdl-respawn-flood", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so the arrival below commits.
            driver.tickUntilWindowExpired();

            // A frame 20 blocks west, hung on a constructed wall block (frames need a vertical face) and
            // anchored on the player's block position. The support sits between the frame and the walking
            // player, so the server's snapped range check trails the block-position anchor and the frame stays
            // paired a little past the block distance, keeping it in range across the walk to the stop window.
            // Its arrival commits a 20-block sample, radius 1, and it never moves, so its book entry stays
            // both-bits-clear for the death flood's removal to sample.
            int frameX = playerBlockPos.getX() - 20;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            // Wait until the client actually received the frame, so the walk starts with the anchor registered
            // (synchronization only; the assertion reads the estimator, not the frame).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));

            walkToDeathWindow(context, driver, frameX, frameZ);

            fixture.server().runCommand("kill @p");
            context.waitForScreen(DeathScreen.class);
            context.clickScreenButton("deathScreen.respawn");
            // Read only after the respawn completes: the new player is alive and the death screen has closed.
            context.waitFor(client -> client.player != null && client.player.isAlive() && client.gui.screen() == null);
            driver.tick(20); // let the client settle after the respawn before the radius read

            Check.that(driver.rangeRadiusChunks(dimension, 5) == 3,
                    "the pre-respawn death flood must calibrate radius 3 and survive the book clear, got "
                            + driver.rangeRadiusChunks(dimension, 5));

            driver.stopAndAwaitSave();
        }
    }

    /**
     * Walk the player due east, straight away from the frame, on real forward-key input, pumping a capture tick every
     * walk tick; releases the key once the frame is {@link #STOP_DISTANCE_BLOCKS} away so the death flood samples
     * inside the radius-3 death-distance window.
     */
    private static void walkToDeathWindow(ClientGameTestContext context, CaptureDriver driver, int frameX,
            int frameZ) {
        // Input locomotion, never a player teleport: a teleport arms the sampler's anomaly window by design and
        // would suppress the death flood's removal sample. Plain walking covers about 0.22 blocks a tick, safely
        // under the SendRangeSampler.FAST_TICK_BLOCKS re-arm threshold (no sprinting, for the same headroom).
        context.runOnClient(client -> {
            client.player.setYRot(-90.0f); // yaw -90 faces plus x (east), directly away from the frame
            client.player.setXRot(0.0f);
        });
        // A reconnect earlier in a multi-scenario run can leave a confirm screen focused, and an open screen
        // suppresses movement input, so dismiss it before driving the forward key or the walk never starts.
        context.runOnClient(client -> client.gui.setScreen(null));
        context.getInput().holdKey(options -> options.keyUp);
        boolean inWindow = false;
        for (int walkTick = 0; walkTick < WALK_TIMEOUT_TICKS && !inWindow; walkTick++) {
            driver.tick(1); // keep pumping capture ticks mid-walk, as the production tick hook does
            Vec3 playerPos = context.computeOnClient(client -> client.player.position());
            double dx = playerPos.x - frameX;
            double dz = playerPos.z - frameZ;
            inWindow = dx * dx + dz * dz >= STOP_DISTANCE_BLOCKS * STOP_DISTANCE_BLOCKS;
        }
        context.getInput().releaseKey(options -> options.keyUp);
        Check.that(inWindow, "the player never walked " + (int) STOP_DISTANCE_BLOCKS
                + " blocks from the frame within " + WALK_TIMEOUT_TICKS + " ticks");
        driver.tick(10); // let the released walk settle so the death flood samples a stable distance
    }
}
