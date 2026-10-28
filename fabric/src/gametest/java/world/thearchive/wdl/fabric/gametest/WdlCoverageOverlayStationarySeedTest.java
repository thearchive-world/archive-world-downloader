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
 * The frames hang at 35 and 44 blocks: the scaled real range ({@code entity-broadcast-range-percentage} 30 of
 * {@code clientTrackingRange(10) * 16}) tops out at 48 blocks so both are received.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayStationarySeedTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());
            long neighborChunk = ChunkPos.asLong(playerChunkPos.x + 1, playerChunkPos.z);

            // Both frames go up BEFORE the download starts, so their spawn packets predate the tee and only the
            // chunk-prime seed registration can anchor them. Anchored on the player's block position, so the distances
            // stay pinned regardless of where in its chunk the player stands.
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
            // Wait until the client actually received both frames, so the prime runs with them loaded.
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .filter(ItemFrame.class::isInstance).count() >= 2);

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-stationary-seed", "wdl-stationary-seed", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            driver.tickUntilWindowExpired();
            driver.tick(10);

            Check.that(context.computeOnClient(client -> client.player.chunkPosition()).equals(playerChunkPos),
                    "the player drifted out of its start chunk; the stationary pin is void");
            // While the range is uncalibrated the overlay read mirrors the saved set, which would satisfy the covered
            // assertion vacuously; a calibrated radius rules that mirror out.
            int radius = driver.rangeRadiusChunks(dimension, 5);
            Check.that(radius >= 1,
                    "the sweep must calibrate at least a 1-chunk radius from the pre-start frames, got " + radius);
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), neighborChunk),
                    "a non-center chunk inside the seeded radius must read covered without a chunk crossing");

            driver.stopAndAwaitSave();
        }
    }
}
