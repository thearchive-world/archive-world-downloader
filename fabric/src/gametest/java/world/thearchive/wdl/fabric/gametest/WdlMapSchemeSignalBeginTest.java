// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.WdlConfig;

@SuppressWarnings("UnstableApiUsage")
public class WdlMapSchemeSignalBeginTest implements FabricClientGameTest {
    private static final int ROAM_BLOCKS = 9 * 16;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            SaveFixture.reset(context.computeOnClient(client -> client.getLevelSource().getBaseDir()),
                    "wdl-scheme-signal");
            BlockPos home = context.computeOnClient(client -> client.player.blockPosition());

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-scheme-signal", "wdl-scheme-signal", DownloadMode.NEW),
                    WdlConfig.DEFAULTS); // remap on by default
            run.tick(5);

            roamTo(context, fixture, server, home.getX() + ROAM_BLOCKS, home);
            run.tick(25);

            Path saveRoot = context
                    .computeOnClient(client -> client.getLevelSource().getBaseDir().resolve("wdl-scheme-signal"));
            Check.that(MapManifest.existsIn(saveRoot),
                    "remap-on must plant the wdl/map-ids scheme signal at world-open, before finalize; it is "
                            + "absent mid-capture at " + MapManifest.pathIn(saveRoot));

            run.stopAndAwaitSave();
        }
    }

    private static void roamTo(ClientGameTestContext context, MultiplayerFixture fixture, TestServerContext server,
            int x, BlockPos home) {
        server.runCommand("tp @a " + x + " " + home.getY() + " " + home.getZ());
        context.waitFor(client -> client.player != null && Math.abs(client.player.getBlockX() - x) <= 2);
        fixture.waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
    }
}
