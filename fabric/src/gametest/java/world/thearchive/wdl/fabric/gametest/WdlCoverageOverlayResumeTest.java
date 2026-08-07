// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Coverage overlay resume axis: a resumed download seeds the overlay with the dimension's prior on-disk coverage, not
 * only this session's re-downloaded chunks. A first NEW capture at spot A writes area A's region files; the player then
 * moves far so area A unloads; a RESUME records at spot B. The resume overlay must hold chunk A, which this run never
 * re-captures (the player is at B), so only the disk seed could have added it. No map overlay mod is on the gametest
 * classpath (XaeroPlus hard-depends on Xaero's maps, whose modal blocks a headless run), so the driver is told the
 * overlay is active to arm the seed.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayResumeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            long chunkA = context.computeOnClient(client -> client.player.chunkPosition().pack());

            // Session 1 (NEW): capture and save area A, so its region files exist on disk.
            CaptureDriver.capture(context,
                    new DownloadTarget("wdl-overlay-resume", "wdl-overlay-resume", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, 20);

            // Move far enough that area A unloads, so the resume run never re-captures chunk A.
            fixture.server().runCommand("tp @a 3000 100 3000");
            context.waitFor(client -> client.player != null && client.player.getX() > 2900);
            fixture.clientWorld().waitForChunksDownload();
            long chunkB = context.computeOnClient(client -> client.player.chunkPosition().pack());
            Check.that(chunkA != chunkB, "the tp did not move the player to a new chunk");

            // Session 2 (RESUME), with the overlay reported active: the first capture tick's flush submits the seed,
            // which reads area A's region headers off disk on the writer thread.
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-overlay-resume", "wdl-overlay-resume", DownloadMode.RESUME),
                    WdlConfig.DEFAULTS, true);
            driver.tick(30);
            // The seed runs on the writer thread, so wait for it to land rather than assuming it already ran.
            context.waitFor(client -> ContainerDriver.contains(driver.overlaySavedChunks(dimension), chunkA));

            long[] overlay = driver.overlaySavedChunks(dimension);
            Check.that(ContainerDriver.contains(overlay, chunkA),
                    "resume overlay lacks the prior on-disk chunk A " + chunkA + " (the seed did not run)");
            Check.that(ContainerDriver.contains(overlay, chunkB),
                    "resume overlay lacks the live-captured chunk B " + chunkB);

            // A resumed prior draws in the covered hue, never suspect: the prior session's path was not observed,
            // so suspect would be a guess. The seed feeds the covered index too, so chunk A is covered; chunk B is
            // covered by this session's live coverage disc around the player.
            context.waitFor(client -> ContainerDriver.contains(driver.overlayCoveredChunks(dimension), chunkA));
            long[] covered = driver.overlayCoveredChunks(dimension);
            Check.that(ContainerDriver.contains(covered, chunkA),
                    "resume covered set lacks the prior on-disk chunk A " + chunkA + " (a resumed prior must draw "
                            + "in the covered hue, not the suspect one)");
            Check.that(ContainerDriver.contains(covered, chunkB),
                    "resume covered set lacks the live-covered chunk B " + chunkB);

            driver.stopAndAwaitSave();
        }
    }
}
