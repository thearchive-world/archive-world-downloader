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

/**
 * Map-id scheme signal, the begin-time crash-resilience pin. {@code MapManifest.schemeMismatch} reads the
 * {@code wdl/map-ids} manifest's presence as the on-disk remap-on marker, its absence being the off-mode signal
 * {@code WdlOffModeMapIdCaptureTest} pins. The manifest's full hash-to-id table is written at finalize, but the
 * presence marker is planted at world-open, before any {@code data/map_<id>.dat}, so a remap-on download interrupted
 * after its map data reaches disk still reads as remap-on rather than falsely as off-scheme on a resume. Without the
 * begin-time plant such a download resumes through a spurious map-id-scheme-mismatch confirm.
 *
 * <p>The interrupt is not injected: the run drives a remap-on capture, forces the first flush (which opens the writer)
 * by roaming out of the keep-hot window, then asserts the manifest is already present while still recording, before any
 * finalize has run, which is exactly the on-disk state a crash at that point would leave.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlMapSchemeSignalBeginTest implements FabricClientGameTest {
    /** Chunks to roam so the starting chunk leaves the keep-hot window (> 7) and the writer opens on its flush. */
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

            // Force the first flush so the writer opens (it opens lazily on the first chunk to flush): roam out of
            // the keep-hot window so the starting chunk drains to disk. This is the mid-capture, pre-finalize state
            // a crash would leave behind.
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

    /** Teleport every player to {@code x} (keeping the home y/z), then wait for arrival and the chunk download. */
    private static void roamTo(ClientGameTestContext context, MultiplayerFixture fixture, TestServerContext server,
            int x, BlockPos home) {
        server.runCommand("tp @a " + x + " " + home.getY() + " " + home.getZ());
        context.waitFor(client -> client.player != null && Math.abs(client.player.getBlockX() - x) <= 2);
        fixture.clientWorld().waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
    }
}
