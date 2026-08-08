// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.nbt.CompoundTag;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * World-output axis: the configured world-open state and game rules reach level.dat. The downloaded world opens at noon
 * ({@code DayTime} 6000) as a fixed world-open invariant, and the default config has the game-rule master on, so the
 * curated safe rule set is written. Both are observable in level.dat's {@code Data}, proving the world-output config is
 * applied to the assembled save, not just the chunks.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlWorldOutputConfigTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-worldout", "wdl-worldout", DownloadMode.NEW), WdlConfig.DEFAULTS, 20);

            CompoundTag data = CaptureReadback.levelData(saveRoot);
            Check.that((data.contains("DayTime") ? data.getLong("DayTime") : -1) == 6000L,
                    "world-output did not open the world at noon (level.dat DayTime 6000): "
                            + (data.contains("DayTime") ? data.getLong("DayTime") : -1));
            Check.that(!data.getCompound("GameRules").isEmpty(),
                    "world-output did not write the curated game rules to level.dat");
        }
    }
}
