// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Stationary seed calibration: a download started in an already-decorated area must calibrate without the player ever
 * moving. Item frames that exist before the download starts are invisible to the packet tee, so the estimator can know
 * them only through the chunk-prime seed registration; seed samples are suppressed inside the sampler's start window
 * ({@code SendRangeSampler.WINDOW_NANOS}), and the one-shot sweep afterwards replays the primed book against the player
 * position. The frames hang at 35 and 44 blocks, both inside [32, 48] on purpose: the scaled real range
 * ({@code entity-broadcast-range-percentage} 30 of {@code clientTrackingRange(10) * 16}) tops out at 48 blocks so both
 * are received, a distance at or under 16 would be nonpositive after the 16-block haircut
 * ({@code SendRangeSampler.HAIRCUT_BLOCKS}) and skipped, and one in (16, 32) would floor to radius 0; either would be a
 * false red. The sweep's post-haircut samples (roughly 19 and 28 blocks) floor to a 1-chunk covered radius under the
 * harness's pinned render distance 5.
 *
 * <p>The covered assertion is the per-tick re-read pin: recordCoveredDisc re-reads the radius every capture tick ahead
 * of its same-chunk early return, so the sweep's radius change recomputes the covered disc while the player stands
 * still, and a non-center chunk inside the seeded radius reads covered although the player never crossed a chunk border
 * (the trail holds exactly one center).
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayStationarySeedTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());
            long neighborChunk = new ChunkPos(playerChunkPos.x() + 1, playerChunkPos.z()).pack();

            // Both frames go up BEFORE the download starts, so their spawn packets predate the tee and only
            // the chunk-prime seed registration can anchor them. Hung on constructed wall blocks (frames
            // need a vertical face), anchored on the player's block position as the suite's range tests do,
            // so the distances stay pinned regardless of where in its chunk the player stands.
            int nearFrameX = playerBlockPos.getX() + 35;
            int farFrameX = playerBlockPos.getX() + 44;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (nearFrameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + nearFrameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            fixture.server().runCommand(
                    "setblock " + (farFrameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + farFrameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            // Wait until the client actually received both frames, so the prime runs with them loaded
            // (synchronization only; the assertions read the estimator and the covered set, not the frames).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .filter(ItemFrame.class::isInstance).count() >= 2);

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-stationary-seed", "wdl-stationary-seed", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // The player never moves from here on. Capture start arms the sampler's suppression window; the
            // prime registers the frames without sampling, and the one-shot sweep after the window replays
            // the book. The extra ticks give recordCoveredDisc its per-tick radius re-read after the sweep.
            driver.tickUntilWindowExpired();
            driver.tick(10);

            Check.that(context.computeOnClient(client -> client.player.chunkPosition()).equals(playerChunkPos),
                    "the player drifted out of its start chunk; the stationary pin is void");
            // Radius first: while the range is uncalibrated the overlay read mirrors the saved set, which
            // would satisfy the covered assertion vacuously; a calibrated radius rules that mirror out.
            int radius = driver.rangeRadiusChunks(dimension, 5);
            Check.that(radius >= 1,
                    "the sweep must calibrate at least a 1-chunk radius from the pre-start frames, got " + radius);
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), neighborChunk),
                    "a non-center chunk inside the seeded radius must read covered without a chunk crossing");

            driver.stopAndAwaitSave();
        }
    }
}
