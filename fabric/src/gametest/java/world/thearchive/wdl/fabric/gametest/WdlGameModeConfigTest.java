// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The player is switched to survival first so the two cases are distinguishable: with the master and
 * {@code openInCreative} on (the default), the download opens in creative despite the survival player, and with the
 * master off it opens in the player's real survival mode.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlGameModeConfigTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            fixture.server().runCommand("gamemode survival @a");
            context.waitFor(client -> client.gameMode != null
                    && client.gameMode.getPlayerMode() == GameType.SURVIVAL);

            Path masterOnRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-on", "wdl-gamemode-on", DownloadMode.NEW), WdlConfig.DEFAULTS, 20);
            CompoundTag masterOnData = CaptureReadback.levelData(masterOnRoot);
            Check.that(
                    (masterOnData.contains("GameType") ? masterOnData.getInt("GameType") : -1) == GameType.CREATIVE
                            .getId(),
                    "world-defaults master on did not force creative over the survival player: "
                            + (masterOnData.contains("GameType") ? masterOnData.getInt("GameType") : -1));

            Properties masterOff = new Properties();
            masterOff.setProperty("overrideWorldDefaults", "false");
            Path masterOffRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-off", "wdl-gamemode-off", DownloadMode.NEW),
                    WdlConfig.parse(masterOff), 20);
            CompoundTag masterOffData = CaptureReadback.levelData(masterOffRoot);
            Check.that(
                    (masterOffData.contains("GameType") ? masterOffData.getInt("GameType") : -1) == GameType.SURVIVAL
                            .getId(),
                    "world-defaults master off did not save the player's real survival mode: "
                            + (masterOffData.contains("GameType") ? masterOffData.getInt("GameType") : -1));

            Properties creativeOff = new Properties();
            creativeOff.setProperty("openInCreative", "false");
            Path creativeOffRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-creative-off", "wdl-gamemode-creative-off", DownloadMode.NEW),
                    WdlConfig.parse(creativeOff), 20);
            CompoundTag creativeOffData = CaptureReadback.levelData(creativeOffRoot);
            Check.that(
                    (creativeOffData.contains("GameType") ? creativeOffData.getInt("GameType")
                            : -1) == GameType.SURVIVAL.getId(),
                    "master on with openInCreative off did not save the player's real survival mode: "
                            + (creativeOffData.contains("GameType") ? creativeOffData.getInt("GameType") : -1));
        }
    }
}
