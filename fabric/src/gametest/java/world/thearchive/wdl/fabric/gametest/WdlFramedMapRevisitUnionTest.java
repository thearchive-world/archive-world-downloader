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

/**
 * Entities axis, the partial-reflush / union pin. The entity packet path re-flushes a chunk whenever its entities
 * stream over more than one pass: a chunk drains and flushes when it leaves the keep-hot window, and a later re-flush
 * holds only the entities the server re-sent since. {@code EntityMerge} unions the on-disk entities into each fresh
 * write by UUID, so a partial re-flush ADDS to rather than REPLACES the prior set. Without the union a re-visited
 * map-art outpost silently loses whole framed maps a prior flush had already saved. This drives that on the live loop
 * and asserts the framed maps survive on disk, the case a single-pass capture ({@link WdlFilledMapCaptureTest}) cannot
 * catch. The union's field-level correctness is owned by the headless {@code EntityMergeTest}; this is the integration
 * proof that the live re-flush reaches it.
 *
 * <p>The drive makes the partial re-flush SUBTRACTIVE on disk, the only shape where the union and a plain replace
 * differ: a re-flush whose fresh set merely re-adds the prior entities is indistinguishable from a replace. Two framed
 * maps are planted in one chunk and captured, the chunk is flushed once (on disk: both frames), one frame is then
 * removed, and a revisit re-streams only the surviving frame so the chunk re-flushes holding a proper subset. A correct
 * union carries the removed frame forward (both frames on disk); a replace would leave only the re-streamed one. The
 * geometry is load-bearing: an entity is re-streamed only by leaving the server's entity-tracking range (about 5 chunks
 * here) and re-entering it, while the chunk must leave the capture's wider keep-hot window (render distance 5 + 2 = 7
 * chunks) to re-flush yet stay within the server's load radius (view-distance 10) so the removal reaches it. A revisit
 * at about 9 chunks satisfies all three.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlFramedMapRevisitUnionTest implements FabricClientGameTest {
    /** The first map id a fresh world issues ({@code getFreeMapId}); both planted frames reference it. */
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

            // First flush: roam out of keep-hot so the chunk drains both frames to disk (on disk: two frames).
            roamTo(context, fixture, server, home.getX() + ROAM_BLOCKS, home);
            run.tick(25);

            // Remove one frame while the chunk is still loaded server-side (it is within the view-distance load
            // radius at this range), so the revisit re-streams only the survivor and the re-flush set is a subset.
            server.runCommand("kill @e[tag=wdlUnionRemoved]");
            run.tick(5);

            // Revisit: back within entity-tracking range so the surviving frame re-spawns over the packet path
            // (the removed one is gone, so the re-streamed set lacks it). Exactly one frame proves the subset.
            roamTo(context, fixture, server, home.getX(), home);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) == 1);
            run.tick(10);

            // Second flush: roam out again so the chunk re-flushes holding only the survivor; the union must
            // carry the removed frame forward from disk.
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
                    .orElseThrow(() -> new AssertionError("data/idcounts.dat is missing"));
            Check.that(idCount >= capturedId,
                    "idcounts map id " + idCount + " is below the captured map id " + capturedId);

            // capturedFrames == 2 distinguishes union from replace only if the first flush drained the chunk to
            // disk before the kill, so the second flush carries the removed frame forward rather than re-streaming
            // it. entitiesCarriedForward is non-zero only on a real merge-back (a fresh write filled from the prior
            // on-disk set), so it pins that precondition; without it the accumulator never drained and the two
            // frames would be a single fresh write, passing capturedFrames == 2 even under a pure replace.
            Check.that(carriedForwardCount(logs) >= 1, "the union path carried no entity forward from disk "
                    + "(entitiesCarriedForward == 0): the first flush never drained, so the two captured frames do "
                    + "not distinguish the union from a replace");
        }
    }

    /**
     * The {@code entitiesCarriedForward} count parsed from the saved-world message ({@code "{} carried forward
     * on re-flush"}), the merge-back signal; 0 if no save line was captured.
     */
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

    /** Summon a framed map at {@code pos} holding {@code filled_map[map_id=0]}, tagged for later removal. */
    private static void summonFramedMap(TestServerContext server, BlockPos pos, String tag) {
        // Hang the frame on a constructed wall (a block to its east, facing west): an item frame needs a solid
        // block behind it, and older bands place frames only on a vertical face, never the floor or ceiling.
        server.runCommand("setblock " + (pos.getX() + 1) + " " + pos.getY() + " " + pos.getZ() + " minecraft:stone");
        server.runCommand("summon minecraft:item_frame " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " {Facing:4b,Tags:[\"" + tag + "\"],Item:{id:\"minecraft:filled_map\",count:1,"
                + "components:{\"minecraft:map_id\":" + MAP_ID + "}}}");
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
