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

@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayIndexTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-overlay", "wdl-overlay", DownloadMode.NEW), WdlConfig.DEFAULTS);
            driver.tick(10);

            String dimension = context.computeOnClient(client -> client.level.dimension().location().toString());
            ChunkPos playerChunkPos = context.computeOnClient(client -> client.player.chunkPosition());
            long playerChunk = playerChunkPos.toLong();
            long farChunk = ChunkPos.asLong(playerChunkPos.x + 4, playerChunkPos.z);
            Check.that(ContainerDriver.contains(driver.overlaySavedChunks(dimension), playerChunk),
                    "overlay index lacks the player's chunk during capture (dimension=" + dimension + ")");
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), playerChunk),
                    "covered index lacks the player's own chunk, which the coverage disc must cover");
            // Discriminates the cold-start mirror from a vacuous pass: while uncalibrated (nothing summoned here) the
            // real coverage disc only ever has radius 0, so a chunk several out could only be covered by the mirror
            // returning the whole saved set.
            Check.that(ContainerDriver.contains(driver.overlaySavedChunks(dimension), farChunk),
                    "far chunk is not saved terrain, so the cold-start mirror assertion below would prove nothing");
            Check.that(ContainerDriver.contains(driver.overlayCoveredChunks(dimension), farChunk),
                    "cold-start mirror did not cover a saved chunk far from the player while uncalibrated");

            Properties entitiesOff = new Properties();
            entitiesOff.setProperty("captureEntities", "false");
            driver.editSettings(WdlConfig.parse(entitiesOff));
            Check.that(driver.overlayCoveredChunks(dimension).length == 0,
                    "switching captureEntities off mid-download left the covered tone drawn");
            Check.that(driver.overlaySavedChunks(dimension).length > 0,
                    "only the covered tone follows captureEntities; the saved highlight has its own toggle");
            driver.editSettings(WdlConfig.DEFAULTS);

            driver.stopAndAwaitSave();
            Check.that(driver.overlaySavedChunks(dimension).length == 0,
                    "overlay index was not cleared once the save completed");
            Check.that(driver.overlayCoveredChunks(dimension).length == 0,
                    "covered index was not cleared once the save completed");

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
