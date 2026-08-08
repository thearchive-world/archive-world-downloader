// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapId;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Filled-maps axis: a viewed map's image lands under the save's {@code data/} directory, the live front of the map
 * archive the unit suite round-trips. A map's image (its {@code MapItemSavedData}) reaches the client only after the
 * server sends it, so the capture images only the maps the client actually received; the run pins both sides of that.
 * An item frame's framed map is an entity carrying an {@code "Item"}, so this also exercises the framed-map remap
 * ({@code LiveCaptureSession.remapEntityItems}) that lifts the framed map's image into {@code data/}.
 *
 * <p>Two framed maps are captured in one run: one whose colors the client received (a real server-side map; its image
 * must be present and well-formed under {@code data/map_*.dat}) and one referencing a map id with no server-side data
 * (its colors are never sent, so it images nothing). Both frames are captured as entities, but only the received map
 * writes an image, so a present image proves the received path reached disk rather than a framed map merely being
 * captured. Field-level pixel fidelity is the unit suite's job ({@code MapArchiveTest}, {@code MapSinkRoundTripTest});
 * this asserts presence, well-formedness, and the {@code idcounts} floor.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlFilledMapCaptureTest implements FabricClientGameTest {
    /** The first map created on a fresh world: {@code getFreeMapId} issues 0 first, so the receiver references it. */
    private static final int RECEIVED_MAP_ID = 0;
    /** A map id with no server-side saved data: the server sends no colors, so the client never receives it. */
    private static final int UNRECEIVED_MAP_ID = 12345;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            int frameY = context.computeOnClient(client -> client.player.blockPosition().getY());
            BlockPos receivedFrame = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 8);
            BlockPos unreceivedFrame = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 10);

            // Register and paint the saved data for map id 0 (a fresh world's getFreeMapId issues 0 first), so the
            // receiver's framed map references a real colored image the server can push; the bogus id is left
            // without saved data. The bands give the captured image content to verify, not a blank 128x128 array.
            server.runOnServer(minecraftServer -> {
                MapItem.create(minecraftServer.overworld(), 0, 0, (byte) 0, true, false);
                MapFixture.paintBands(minecraftServer.overworld(), new MapId(RECEIVED_MAP_ID));
            });

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-filled-map", "wdl-filled-map", DownloadMode.NEW), WdlConfig.DEFAULTS);
            run.tick(5);

            MapFixture.summonFramedMap(server, receivedFrame, RECEIVED_MAP_ID);
            MapFixture.summonFramedMap(server, unreceivedFrame, UNRECEIVED_MAP_ID);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) >= 2
                    && client.level.getMapData(new MapId(RECEIVED_MAP_ID)) != null);
            Check.that(context.computeOnClient(client -> client.level.getMapData(new MapId(UNRECEIVED_MAP_ID)) == null),
                    "the bogus framed map resolved client-side; the negative is not a genuine never-received case");
            Path saveRoot = run.tick(20).stopAndAwaitSave();

            List<Path> images = CaptureReadback.mapDataFiles(saveRoot);
            Check.that(images.size() == 1,
                    "expected exactly the received map's image under data/, got " + images.size() + ": " + images);
            CompoundTag mapImage = CaptureReadback.readDataInner(images.get(0));
            Check.that(CaptureReadback.isWellFormedMapImage(mapImage),
                    "the captured map image carries no 128x128 colors array: " + images.get(0));
            long colors = CaptureReadback.distinctNonZeroColors(mapImage);
            Check.that(colors >= 3, "the captured map carried too few distinct colors (" + colors + "); the "
                    + "painted color data did not round-trip to disk");
            int capturedId = CaptureReadback.mapDataIds(saveRoot).get(0);
            int idCount = CaptureReadback.idCountsMax(saveRoot)
                    .orElseThrow(() -> new AssertionError("data/idcounts.dat is missing"));
            Check.that(idCount >= capturedId,
                    "idcounts map id " + idCount + " is below the captured map id " + capturedId);

            long capturedFrames = CaptureReadback.readEntityChunk(saveRoot, chunk)
                    .map(tag -> CaptureReadback.entities(tag).stream()
                            .filter(entity -> entity.getString("id").equals("minecraft:item_frame"))
                            .count())
                    .orElse(0L);
            Check.that(capturedFrames == 2,
                    "expected both framed maps captured as entities (the never-received one images nothing but is "
                            + "still captured), got " + capturedFrames);
        }
    }
}
