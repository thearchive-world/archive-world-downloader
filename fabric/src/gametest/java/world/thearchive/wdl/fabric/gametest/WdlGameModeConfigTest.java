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
 * Game-mode axis: the world-defaults master governs the game type written to level.dat, the one capture-side
 * consequence of the master and the half no unit test can reach (the session reads the live client game mode,
 * minecraft.gameMode, not a value it is handed). The player is switched to survival first so the two cases are
 * distinguishable: with the master and openInCreative on (the default), the download opens in creative despite the
 * survival player, and with the master off it opens in the player's real survival mode. Level.dat's {@code GameType} is
 * what a singleplayer open reads, set from the captured player's game type.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlGameModeConfigTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            // The fixture spawns the player in creative; drop to survival and wait until the client observes it,
            // which both applies the switch and proves the setup command took (runCommand is otherwise silent).
            fixture.server().runCommand("gamemode survival @a");
            context.waitFor(client -> client.gameMode != null
                    && client.gameMode.getPlayerMode() == GameType.SURVIVAL);

            Path masterOnRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-on", "wdl-gamemode-on", DownloadMode.NEW), WdlConfig.DEFAULTS, 20);
            CompoundTag masterOnData = CaptureReadback.levelData(masterOnRoot);
            Check.that(masterOnData.getIntOr("GameType", -1) == GameType.CREATIVE.getId(),
                    "world-defaults master on did not force creative over the survival player: "
                            + masterOnData.getIntOr("GameType", -1));

            Properties masterOff = new Properties();
            masterOff.setProperty("overrideWorldDefaults", "false");
            Path masterOffRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-off", "wdl-gamemode-off", DownloadMode.NEW),
                    WdlConfig.parse(masterOff), 20);
            CompoundTag masterOffData = CaptureReadback.levelData(masterOffRoot);
            Check.that(masterOffData.getIntOr("GameType", -1) == GameType.SURVIVAL.getId(),
                    "world-defaults master off did not save the player's real survival mode: "
                            + masterOffData.getIntOr("GameType", -1));

            Properties creativeOff = new Properties();
            creativeOff.setProperty("openInCreative", "false");
            Path creativeOffRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-gamemode-creative-off", "wdl-gamemode-creative-off", DownloadMode.NEW),
                    WdlConfig.parse(creativeOff), 20);
            CompoundTag creativeOffData = CaptureReadback.levelData(creativeOffRoot);
            Check.that(creativeOffData.getIntOr("GameType", -1) == GameType.SURVIVAL.getId(),
                    "master on with openInCreative off did not save the player's real survival mode: "
                            + creativeOffData.getIntOr("GameType", -1));
        }
    }
}
