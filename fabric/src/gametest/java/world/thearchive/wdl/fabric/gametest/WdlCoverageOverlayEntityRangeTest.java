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
 * Vanilla pairs a decoration to a player when the horizontal distance is within {@code clientTrackingRange(10) * 16}
 * blocks scaled by the percentage (capped by the view distance); at 30 percent that is 48 blocks, 3 chunks. The
 * gametest harness pins the client render distance to 5, so a saved chunk 4 chunks out is terrain the capture saves but
 * decorations never reach.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayEntityRangeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());
            long playerChunk = playerChunkPos.toLong();
            long innerChunk = ChunkPos.asLong(playerChunkPos.x + 2, playerChunkPos.z);
            long outerChunk = ChunkPos.asLong(playerChunkPos.x + 4, playerChunkPos.z);

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-range", "wdl-range", DownloadMode.NEW), WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so the arrival below commits.
            driver.tickUntilWindowExpired();

            // A decoration within the real ~48-block range, the estimator's calibration sample: an item frame 40 blocks
            // east of the player's block position. Anchored on the block position, not a chunk center, so the received
            // distance is about 40 blocks no matter where in its chunk the player stands.
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

            driver.tick(40);
            context.waitFor(client -> ContainerDriver.contains(driver.overlaySavedChunks(dimension), outerChunk));

            long[] covered = driver.overlayCoveredChunks(dimension);
            long[] saved = driver.overlaySavedChunks(dimension);
            Check.that(ContainerDriver.contains(saved, outerChunk),
                    "outer chunk must be saved terrain for the suspect assertion to mean anything");
            Check.that(ContainerDriver.contains(covered, playerChunk), "player chunk must be covered");
            Check.that(ContainerDriver.contains(covered, innerChunk), "a chunk within the real range must be covered");
            Check.that(!ContainerDriver.contains(covered, outerChunk),
                    "a saved chunk beyond the real ~3-chunk range must be suspect, not covered");

            driver.stopAndAwaitSave();
        }
    }
}
