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

/**
 * A passenger-boosted vehicle must never calibrate the send-range estimator. A ridden vehicle pairs to the player at
 * its passenger's boosted tracking range, so its arrival distance over-claims the real send range; the sampler drops
 * that arrival by the same-flush commit rule and marks the vehicle ridden, so no arrival and no later removal of the
 * vehicle can ever feed the estimator. This run pins both drops at once.
 *
 * <p>On a reduced {@code entity-broadcast-range-percentage} server (30 percent) an armor stand carrying an end-crystal
 * passenger is summoned 70 blocks away. The armor stand is a decoration, so it always qualifies for sampling, and it
 * reaches 70 blocks only because the crystal boosts the pair's tracking range: the stand's own scaled range is 48
 * blocks, while the crystal's range 16 boosts the effective range to min(76, 80) = 76, so the pair pairs at 70. Both
 * leak shapes land past 64 and are distinguishable from uncalibrated: a commit-rule leak would sample the 70-block
 * arrival and floor to radius 4, a ridden-bit leak would sample the 54-block post-haircut removal and floor to radius
 * 3. Nothing here can honestly sample, so the dimension must stay uncalibrated for the whole run (the flat fixture
 * world spawns no mobs and holds no other decoration; the crystal is range 16, not a decoration, so it never
 * qualifies).
 *
 * <p>Killing the crystal un-boosts the stand, whose range drops back to 48, so the server prunes the now out-of-range
 * stand and tees its removal. The permanent ridden bit ({@code SendRangeSampler.markRidden}, no path clears it) must
 * still drop that removal, so the dimension stays uncalibrated after the un-boost prune.
 *
 * <p>This exercises only the vehicle slot of the ridden marking. The passenger-slot case, a ridden qualifying passenger
 * whose stale boarding anchor could feed a sample, has no executable pin anywhere and stays review-pinned.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayBoostedPairingTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connectWithEntityRange(context, 30)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().location().toString());
            BlockPos playerBlockPos = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-boosted-pairing", "wdl-boosted-pairing", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, true);
            // Capture start arms the sampler's suppression window; wait it out so an honest arrival would commit,
            // proving the drops below, not the window, are what keep the dimension uncalibrated.
            driver.tickUntilWindowExpired();

            // An armor stand carrying an end-crystal passenger, spawned as one pre-mounted pair so the pairing
            // bundle self-contains the SetPassengers that both drops rest on. Anchored on the player's block
            // position at a deterministic 70 blocks east; the boosted range 76 pairs it, the un-boosted range 48
            // would not.
            int standX = playerBlockPos.getX() + 70;
            int standZ = playerBlockPos.getZ();
            int standY = playerBlockPos.getY();
            fixture.server().runCommand("summon minecraft:armor_stand " + standX + " " + standY + " " + standZ
                    + " {Passengers:[{id:\"minecraft:end_crystal\"}]}");
            // Wait until the client actually received the stand, so the run reads with the pair present
            // (synchronization only; the assertion reads the estimator, not the stand).
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(96)).stream()
                    .anyMatch(ArmorStand.class::isInstance));
            driver.tick(20); // pump ticks so an honest sample, if any leaked, would have committed by now

            Check.that(!driver.isCalibrated(dimension),
                    "the boosted pair's arrival must stay dropped, leaving the dimension uncalibrated");

            // Kill the crystal: the stand un-boosts to range 48 and, now out of range at 70, is pruned, teeing
            // its removal. The permanent ridden bit must still drop that removal sample.
            fixture.server().runCommand("kill @e[type=end_crystal]");
            context.waitFor(client -> client.level
                    .getEntities((Entity) null, client.player.getBoundingBox().inflate(96)).stream()
                    .noneMatch(ArmorStand.class::isInstance));
            driver.tick(60); // past the un-boost prune, so the stand's removal has been teed and dropped

            Check.that(!driver.isCalibrated(dimension),
                    "the un-boosted stand's ridden removal must stay dropped, leaving the dimension uncalibrated");

            driver.stopAndAwaitSave();
        }
    }
}
