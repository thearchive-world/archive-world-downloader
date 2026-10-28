// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.gamerules.GameRules;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlWorldOutputConfigTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-worldout", "wdl-worldout", DownloadMode.NEW), WdlConfig.DEFAULTS, 20);

            RegistryAccess registries = context.computeOnClient(client -> client.level.registryAccess());
            long dayTime = CaptureReadback.overworldDayTime(saveRoot, registries);
            Check.that(dayTime == 6000L, "world-output did not open the world at noon "
                    + "(world_clocks.dat overworld clock 6000): " + dayTime);
            GameRules rules = CaptureReadback.gameRules(saveRoot);
            Check.that(rules.get(GameRules.KEEP_INVENTORY) && !rules.get(GameRules.SPAWN_MOBS),
                    "world-output did not write the curated safe game rules to game_rules.dat");
        }
    }
}
