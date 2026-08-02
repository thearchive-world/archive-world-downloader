// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Guardrail for the non-decoration calibration path: on a reduced {@code entity-broadcast-range-percentage} server with
 * no decoration in sight, a mob that shares the decorations' vanilla tracking range of 10 must still calibrate the
 * send-range estimator. Vanilla pairs a cow to a player when the horizontal distance is within
 * {@code clientTrackingRange(10) * 16} blocks scaled by the percentage (capped by the view distance); at 30 percent
 * that is 48 blocks, the same real range as a decoration, because the cow shares its range. A cow summoned 35 blocks
 * east of the player sits within that range, so the client receives it and its arrival feeds the estimator the raw
 * 35-block distance, which floors to 2 chunks: a saved chunk 2 out resolves covered while a saved chunk 4 out stays
 * entity-suspect. Only qualifying range-10 types feed the estimator, so a shorter-range mob (a spider's vanilla range
 * is 8) is never sampled.
 *
 * <p>The cow is {@code NoAI} and anchored on the player's block position, not a chunk center, so the received distance
 * is a deterministic ~35 blocks regardless of where in its chunk the player stands, keeping the floored calibration
 * radius 2 chunks and matching the inner-chunk assertion below. The test waits out the sampler's start suppression
 * window before the summon, so the run pins that an arrival outside the window commits and measures the scaled truth.
 * Per the gametest ground rules the assertions read only the effect (the covered and saved sets), never that the summon
 * or the property took.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayMobRangeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());
            long playerChunk = playerChunkPos.toLong();
            long innerChunk = ChunkPos.asLong(playerChunkPos.x + 2, playerChunkPos.z); // ~32 blocks: covered
            long outerChunk = ChunkPos.asLong(playerChunkPos.x + 4, playerChunkPos.z); // ~64 blocks: suspect

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-mob-range", "wdl-mob-range", DownloadMode.NEW), WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so the arrival below commits.
            driver.tickUntilWindowExpired();

            // A non-decoration entity within the real 48-block range, the estimator's calibration sample: a cow 35
            // blocks east of the player's block position, tracking at clientTrackingRange 10, so its arrival feeds
            // the estimator the raw 35-block distance, which floors to 2 chunks. Anchored on the block position,
            // not a chunk center, so the received distance is ~35 blocks no matter where in its chunk the player
            // stands. NoAI so it stays put between summon and receipt. Summoned after the start window expired, so
            // its arrival commits a sample instead of being suppressed.
            int cowX = playerBlockPos.getX() + 35;
            int cowZ = playerBlockPos.getZ();
            int cowY = playerBlockPos.getY();
            fixture.server().runCommand(
                    "summon minecraft:cow " + cowX + " " + cowY + " " + cowZ + " {NoAI:1b}");
            // Wait until the client actually received the cow, so the recording ticks below run with the sample
            // present (synchronization only; the range assertions read the covered set, not the cow).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(64)).stream()
                    .anyMatch(Cow.class::isInstance));

            driver.tick(40); // let the estimator calibrate from the received cow and terrain fill
            // The writer thread may not have flushed the outer chunk yet under load; poll rather than assert once.
            context.waitFor(client -> ContainerDriver.contains(driver.overlaySavedChunks(dimension), outerChunk));

            long[] covered = driver.overlayCoveredChunks(dimension);
            long[] saved = driver.overlaySavedChunks(dimension);
            Check.that(ContainerDriver.contains(saved, outerChunk),
                    "outer chunk must be saved terrain for the suspect assertion to mean anything");
            Check.that(ContainerDriver.contains(covered, playerChunk), "player chunk must be covered");
            Check.that(ContainerDriver.contains(covered, innerChunk), "a chunk within the real range must be covered");
            Check.that(!ContainerDriver.contains(covered, outerChunk),
                    "a saved chunk beyond the measured range must be suspect, not covered");

            driver.stopAndAwaitSave();
        }
    }
}
