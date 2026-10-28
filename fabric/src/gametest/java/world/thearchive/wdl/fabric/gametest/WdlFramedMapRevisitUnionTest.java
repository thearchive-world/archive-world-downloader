// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
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

@SuppressWarnings("UnstableApiUsage")
public class WdlFramedMapRevisitUnionTest implements FabricClientGameTest {
    /** The first map id a fresh world issues ({@code getFreeMapId}). */
    private static final int MAP_ID = 0;
    /** Chunks to roam so the planted chunk leaves keep-hot (> 7) yet stays loaded server-side (<= 10). */
    private static final int ROAM_BLOCKS = 9 * 16;
    /** The per-class logger name {@code LiveCaptureSession} reports the saved-world re-flush tally under. */
    private static final String SESSION_LOGGER = "world.thearchive.wdl.adapter.LiveCaptureSession";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context);
                LogCapture logs = new LogCapture(SESSION_LOGGER)) {
            TestServerContext server = fixture.server();

            BlockPos home = context.computeOnClient(client -> client.player.blockPosition());
            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            int frameY = home.getY();
            BlockPos frameKept = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 8);
            BlockPos frameRemoved = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 10);

            server.runOnServer(minecraftServer -> {
                MapItem.create(minecraftServer.overworld(), 0, 0, (byte) 0, true, false);
                MapFixture.paintBands(minecraftServer.overworld(), new MapId(MAP_ID));
            });

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-framed-map-union", "wdl-framed-map-union", DownloadMode.NEW),
                    WdlConfig.DEFAULTS);
            run.tick(5);

            summonFramedMap(server, frameKept, "wdlUnionKept");
            summonFramedMap(server, frameRemoved, "wdlUnionRemoved");
            context.waitFor(client -> ContainerDriver.framedMapCount(client) >= 2
                    && client.level.getMapData(new MapId(MAP_ID)) != null);
            run.tick(5);

            roamTo(context, fixture, server, home.getX() + ROAM_BLOCKS, home);
            run.tick(25);

            // Remove one frame, so the revisit re-streams only the survivor and the re-flush set is a subset.
            server.runCommand("kill @e[tag=wdlUnionRemoved]");
            run.tick(5);

            roamTo(context, fixture, server, home.getX(), home);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) == 1);
            run.tick(10);

            roamTo(context, fixture, server, home.getX() + ROAM_BLOCKS, home);
            Path saveRoot = run.tick(25).stopAndAwaitSave();

            long capturedFrames = CaptureReadback.readEntityChunk(saveRoot, chunk)
                    .map(tag -> CaptureReadback.entities(tag).stream()
                            .filter(entity -> entity.getString("id").orElse("").equals("minecraft:item_frame"))
                            .count())
                    .orElse(0L);
            Check.that(capturedFrames == 2, "the partial re-flush lost or duplicated a framed map: expected both "
                    + "frames unioned on disk (a replace would leave only the re-streamed one), got " + capturedFrames);

            Check.that(!CaptureReadback.mapDataFiles(saveRoot).isEmpty(),
                    "the surviving framed maps' image is absent from data/ after the partial re-flush");
            Check.that(CaptureReadback.isWellFormedMapImage(
                    CaptureReadback.readDataInner(CaptureReadback.mapDataFiles(saveRoot).get(0))),
                    "the carried-forward map image is not well-formed");
            int capturedId = CaptureReadback.mapDataIds(saveRoot).get(0);
            int idCount = CaptureReadback.idCountsMax(saveRoot)
                    .orElseThrow(() -> new AssertionError("the map id counter file is missing"));
            Check.that(idCount >= capturedId,
                    "idcounts map id " + idCount + " is below the captured map id " + capturedId);

            Check.that(carriedForwardCount(logs) >= 1, "the union path carried no entity forward from disk "
                    + "(entitiesCarriedForward == 0): the first flush never drained, so the two captured frames do "
                    + "not distinguish the union from a replace");
        }
    }

    private static int carriedForwardCount(LogCapture logs) {
        String marker = " carried forward on re-flush";
        return logs.events().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .filter(message -> message.contains(marker))
                .mapToInt(message -> {
                    int end = message.indexOf(marker);
                    int start = end;
                    while (start > 0 && Character.isDigit(message.charAt(start - 1))) {
                        start--;
                    }
                    return start < end ? Integer.parseInt(message.substring(start, end)) : 0;
                })
                .max()
                .orElse(0);
    }

    private static void summonFramedMap(TestServerContext server, BlockPos pos, String tag) {
        // Hang the frame on a constructed wall (a block to its east, facing west): an item frame needs a solid block
        // behind it.
        server.runCommand("setblock " + (pos.getX() + 1) + " " + pos.getY() + " " + pos.getZ() + " minecraft:stone");
        server.runCommand("summon minecraft:item_frame " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " {Facing:4b,Tags:[\"" + tag + "\"],Item:{id:\"minecraft:filled_map\",count:1,"
                + "components:{\"minecraft:map_id\":" + MAP_ID + "}}}");
    }

    private static void roamTo(ClientGameTestContext context, MultiplayerFixture fixture, TestServerContext server,
            int x, BlockPos home) {
        server.runCommand("tp @a " + x + " " + home.getY() + " " + home.getZ());
        context.waitFor(client -> client.player != null && Math.abs(client.player.getBlockX() - x) <= 2);
        fixture.clientWorld().waitForChunksDownload();
        context.waitFor(client -> client.player != null && client.level != null);
    }
}
