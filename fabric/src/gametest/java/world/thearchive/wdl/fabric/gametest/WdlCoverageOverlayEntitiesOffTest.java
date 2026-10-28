// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlCoverageOverlayEntitiesOffTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            String dimension = context.computeOnClient(client -> client.level.dimension().location().toString());

            Properties entitiesOff = new Properties();
            entitiesOff.setProperty("captureEntities", "false");
            entitiesOff.setProperty("renderCoverageOverlay", "true");
            CaptureDriver driver = CaptureDriver.start(context,
                    new DownloadTarget("wdl-entities-off", "wdl-entities-off", DownloadMode.NEW),
                    WdlConfig.parse(entitiesOff));
            driver.tick(10);

            long[] saved = driver.overlaySavedChunks(dimension);
            long[] covered = driver.overlayCoveredChunks(dimension);
            Check.that(saved.length > 0, "entities-off run captured no terrain, so the covered assertion below "
                    + "would prove nothing");
            Check.that(covered.length == 0,
                    "entities-off covered set must be empty; it instead mirrored the saved set as if entity "
                            + "capture were on");

            Properties entitiesOn = new Properties();
            entitiesOn.setProperty("captureEntities", "true");
            entitiesOn.setProperty("renderCoverageOverlay", "true");
            driver.editSettings(WdlConfig.parse(entitiesOn));
            driver.tick(10);

            Check.that(driver.overlaySavedChunks(dimension).length > 0,
                    "the saved set emptied after the settings edit, so the covered assertion below would prove "
                            + "nothing");
            Check.that(driver.overlayCoveredChunks(dimension).length == 0,
                    "switching captureEntities on mid-download made the overlay claim entity coverage for a "
                            + "session that latched entity capture off and is capturing none");

            driver.stopAndAwaitSave();
        }
    }
}
