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
 * Guardrail for the measured entity send range: a server with a reduced {@code
 * entity-broadcast-range-percentage} sends static decorations over a shorter real range than the client can read, so a
 * saved chunk beyond that real range must resolve entity-suspect, not covered. Vanilla pairs a decoration to a player
 * when the horizontal distance is within {@code clientTrackingRange(10) * 16} blocks scaled by the percentage (capped
 * by the view distance); at 30 percent that is 48 blocks, 3 chunks. The gametest harness pins the client render
 * distance to 5, so a saved chunk 4 chunks out is terrain the capture saves but decorations never reach. A fixed
 * assumed radius such as {@code min(10, renderDistance)} covers the whole 5-chunk disc and wrongly marks that chunk
 * covered; only a covered radius driven by the measured send range resolves it suspect, which is what this run pins.
 *
 * <p>An item frame summoned 40 blocks east of the player's block position sits within the real 48-block range, so the
 * client receives it and its arrival feeds the estimator a sample; anchoring on the block position rather than a chunk
 * center keeps the received distance a deterministic 40 blocks regardless of where in its own chunk the player stands,
 * so the floored calibration radius is always at least 2 chunks, matching the inner-chunk assertion below. The test
 * waits out the sampler's start suppression window before the summon, so the run pins that an arrival outside the
 * window commits and measures the scaled truth. Per the gametest ground rules the assertions read only the effect (the
 * covered and saved sets), never that the summon or the property took.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayEntityRangeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());
            long playerChunk = playerChunkPos.pack();
            long innerChunk = new ChunkPos(playerChunkPos.x() + 2, playerChunkPos.z()).pack(); // ~32 blocks: covered
            long outerChunk = new ChunkPos(playerChunkPos.x() + 4, playerChunkPos.z()).pack(); // ~64 blocks: suspect

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-range", "wdl-range", DownloadMode.NEW), WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so the arrival below commits.
            driver.tickUntilWindowExpired();

            // A decoration within the real ~48-block range, the estimator's calibration sample: an item frame
            // 40 blocks east of the player's block position, hung on a constructed wall block (frames need a
            // vertical face). Anchored on the block position, not a chunk center, so the received distance is
            // exactly 40 blocks no matter where in its chunk the player stands. Summoned after the start window
            // expired, so its arrival commits a sample instead of being suppressed.
            int frameX = playerBlockPos.getX() + 40;
            int frameZ = playerBlockPos.getZ();
            int frameY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "setblock " + (frameX + 1) + " " + frameY + " " + frameZ + " minecraft:stone");
            fixture.server().runCommand(
                    "summon minecraft:item_frame " + frameX + " " + frameY + " " + frameZ + " {Facing:4b}");
            // Wait until the client actually received the frame, so the recording ticks below run with the
            // sample present (synchronization only; the range assertions read the covered set, not the frame).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(ItemFrame.class::isInstance));

            driver.tick(40); // let the estimator calibrate from received decorations and terrain fill
            // The writer thread may not have flushed the outer chunk yet under load; poll rather than assert once.
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
