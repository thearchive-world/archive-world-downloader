// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.ChunkPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Coverage overlay data axis (no map overlay mod needed, this proves the wdl side): a real capture populates the
 * off-thread saved-chunk index under the live client dimension id, and stop clears it. It also pins the
 * renderCoverageOverlay toggle: a capture at the same spawn with the overlay off yields an empty highlight set even
 * while chunks are captured. The render integration itself is not exercised headless; this guards the record-site write
 * + facade + clear-on-stop + the overlay-enabled gate.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayIndexTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-overlay", "wdl-overlay", DownloadMode.NEW), WdlConfig.DEFAULTS);
            driver.tick(10); // load + capture the render-distance square (the player's own chunk is captured first)

            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            long playerChunk = playerChunkPos.toLong();
            long farChunk = ChunkPos.asLong(playerChunkPos.x + 4, playerChunkPos.z);
            Check.that(ContainerDriver.contains(driver.overlaySavedChunks(dimension), playerChunk),
                    "overlay index lacks the player's chunk during capture (dimension=" + dimension + ")");
            // The two-tone covered axis: the player's own chunk sits at the center of the coverage disc, so it
            // must be covered (drawn in the user hue), not entity-suspect.
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), playerChunk),
                    "covered index lacks the player's own chunk, which the coverage disc must cover");
            // Discriminates the cold-start mirror from a vacuous pass: while uncalibrated (nothing summoned here)
            // the real coverage disc only ever has radius 0, so a chunk several out could only be covered by the
            // mirror returning the whole saved set. First prove it is saved terrain, then that it reads covered.
            Check.that(ContainerDriver.contains(driver.overlaySavedChunks(dimension), farChunk),
                    "far chunk is not saved terrain, so the cold-start mirror assertion below would prove nothing");
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), farChunk),
                    "cold-start mirror did not cover a saved chunk far from the player while uncalibrated");

            driver.stopAndAwaitSave();
            Check.that(driver.overlaySavedChunks(dimension).length == 0,
                    "overlay index was not cleared after stop");
            Check.that(driver.overlayCoveredChunks(dimension).length == 0,
                    "covered index was not cleared after stop");

            // Overlay toggled off: the raw index still fills as chunks are captured (the write is un-gated), but
            // the overlay read returns empty. Asserting both in this one run isolates the renderCoverageOverlay
            // gate, so a regression that instead gated the write path would fail here rather than pass vacuously.
            Properties overlayOff = new Properties();
            overlayOff.setProperty("renderCoverageOverlay", "false");
            CaptureDriver offDriver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-overlay-off", "wdl-overlay-off", DownloadMode.NEW),
                    WdlConfig.parse(overlayOff));
            offDriver.tick(10);
            Check.that(offDriver.rawSavedChunks(dimension).length > 0,
                    "overlay-off run captured no chunks, so the empty gated read below would prove nothing");
            Check.that(offDriver.overlaySavedChunks(dimension).length == 0,
                    "overlay toggled off must yield an empty highlight set while chunks are captured");
            offDriver.stopAndAwaitSave();
        }
    }
}
