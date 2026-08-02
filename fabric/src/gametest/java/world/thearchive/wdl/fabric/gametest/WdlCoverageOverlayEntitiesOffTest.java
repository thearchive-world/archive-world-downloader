// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Guardrail for the entities-off overlay: with {@code captureEntities} off, no entity is ever captured, so the covered
 * read must draw every saved chunk suspect, never the cold-start single-tone mirror (which would claim entities were
 * saved in a mode where none are). The cold-start mirror exists to hide a spurious suspect boundary before the send
 * range is known; that premise does not hold when entity capture itself is off, so the covered set must be empty
 * regardless of how much terrain is saved.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayEntitiesOffTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().identifier().toString());

            Properties entitiesOff = new Properties();
            entitiesOff.setProperty("captureEntities", "false");
            entitiesOff.setProperty("renderCoverageOverlay", "true");
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-entities-off", "wdl-entities-off", DownloadMode.NEW),
                    WdlConfig.parse(entitiesOff));
            driver.tick(10); // load + capture the render-distance square

            long[] saved = driver.overlaySavedChunks(dimension);
            long[] covered = driver.overlayCoveredChunks(dimension);
            Check.that(saved.length > 0, "entities-off run captured no terrain, so the covered assertion below "
                    + "would prove nothing");
            Check.that(covered.length == 0,
                    "entities-off covered set must be empty; it instead mirrored the saved set as if entity "
                            + "capture were on");

            driver.stopAndAwaitSave();
        }
    }
}
