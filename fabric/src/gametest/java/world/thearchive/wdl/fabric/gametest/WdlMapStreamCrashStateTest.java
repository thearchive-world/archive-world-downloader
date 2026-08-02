// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapId;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;
import world.thearchive.wdl.core.browse.DownloadFolders;
import world.thearchive.wdl.core.report.DownloadReportStore;

/**
 * Streamed-map crash durability: a captured map's {@code data/map_<id>.dat} lands on disk during capture, not at
 * finalize, so a crashed session keeps the maps it already saw. The run drives a remap-on capture, forces the framed
 * map's chunk to flush by roaming out of the keep-hot window (which captures the frame entity and streams its map),
 * then asserts the mid-capture on-disk state a crash at that point would leave: the map file present under
 * {@code data/} and the pending crash sentinel marking the folder resumable. A second RESUME session then proves the
 * streamed file survives the resume merge with the {@code idcounts} floor intact.
 *
 * <p>The interrupt is not injected: as in {@link WdlMapSchemeSignalBeginTest}, the mid-capture, pre-finalize on-disk
 * state is exactly the state a crash would leave behind.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlMapStreamCrashStateTest implements FabricClientGameTest {
    /** The first map created on a fresh world: {@code getFreeMapId} issues 0 first, so the frame references it. */
    private static final int RECEIVED_MAP_ID = 0;
    /** Chunks to roam so the starting chunk leaves the keep-hot window (> 7) and the writer opens on its flush. */
    private static final int ROAM_BLOCKS = 9 * 16;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            Path savesDirectory = context.computeOnClient(client -> client.getLevelSource().getBaseDir());
            SaveFixture.reset(savesDirectory, "wdl-map-stream-crash");
            BlockPos home = context.computeOnClient(client -> client.player.blockPosition());
            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            BlockPos framePos = new BlockPos(chunk.getMinBlockX() + 8, home.getY(), chunk.getMinBlockZ() + 8);

            server.runOnServer(minecraftServer -> {
                MapItem.create(minecraftServer.overworld(), 0, 0, (byte) 0, true, false);
                MapFixture.paintBands(minecraftServer.overworld(), new MapId(RECEIVED_MAP_ID));
            });
            MapFixture.summonFramedMap(server, framePos, RECEIVED_MAP_ID);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) >= 1
                    && client.level.getMapData(new MapId(RECEIVED_MAP_ID)) != null);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-map-stream-crash", "wdl-map-stream-crash", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            // Force the first flush so the writer opens and the frame's chunk drains: the frame entity is
            // captured on that flush, which streams its map. This is the mid-capture, pre-finalize state a
            // crash would leave behind.
            roamTo(context, fixture, server, home.getX() + ROAM_BLOCKS, home);

            Path saveRoot = savesDirectory.resolve("wdl-map-stream-crash");
            // The streamed write is a queued writer-thread task, so poll for it bounded rather than at a fixed
            // tick count (the suite is load-sensitive).
            boolean streamed = false;
            for (int i = 0; i < 40 && !streamed; i++) {
                run.tick(5);
                streamed = !CaptureReadback.mapDataIds(saveRoot).isEmpty();
            }
            Check.that(streamed,
                    "the framed map must stream to data/map_<id>.dat mid-capture, before finalize; data/ holds none");
            Check.that(DownloadFolders.isWdlManaged(saveRoot),
                    "the crash sentinel must mark the mid-capture folder resumable");
            Check.that(Files.exists(DownloadReportStore.pendingFile(saveRoot))
                    && !Files.exists(DownloadReportStore.machineFile(saveRoot)),
                    "mid-capture means the pending sentinel, not the completed record");

            run.stopAndAwaitSave();

            CaptureDriver.capture(context,
                    new DownloadTarget("wdl-map-stream-crash", "wdl-map-stream-crash", DownloadMode.RESUME),
                    WdlConfig.DEFAULTS, 30);
            List<Integer> ids = CaptureReadback.mapDataIds(saveRoot);
            Check.that(!ids.isEmpty(), "the streamed map file must survive the resume");
            int highest = Collections.max(ids);
            Check.that(CaptureReadback.idCountsMax(saveRoot).orElse(-1) >= highest,
                    "idcounts must floor at or above every captured id after the resume");
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
