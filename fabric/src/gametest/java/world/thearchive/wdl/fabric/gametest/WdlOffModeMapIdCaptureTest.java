// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.MapManifest;
import world.thearchive.wdl.core.WdlConfig;

/**
 * Off-mode map-id axis: with {@code remapMapIds=false} a captured filled map keeps the server's original map id rather
 * than being re-keyed to a dense archive id. The on-mode remap ({@code WdlFilledMapCaptureTest}) proves the
 * content-dedup rewrite; this pins the opposite knob, that off-mode is an identity pass on all three id surfaces at
 * once: the {@code data/map_<id>.dat} file name, the framed item's {@code minecraft:map_id} component, and the absent
 * {@code wdl/map-ids} manifest whose non-presence is the on-disk mode signal.
 *
 * <p>The map is registered at a deliberately non-dense server id ({@link #ORIGINAL_MAP_ID}) so a densifying regression
 * is visible: on-mode would write this map as {@code map_0.dat}, so a kept {@code map_1234.dat} proves the original id
 * survived rather than merely matching a coincidentally-dense value. The map is painted and hung in an item frame the
 * client can see, so the server pushes its colors ({@code ServerEntity}'s framed map-update path) and the received
 * image lands under the original id; a present, well-formed image with the painted colors is what proves the id-kept
 * file is the real received map, not an empty placeholder at the right name. Like the sibling, the frame is summoned
 * mid-capture so the inbound entity tee captures it (this uses {@link CaptureDriver#start} rather than the one-shot
 * {@code capture} for that reason).
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlOffModeMapIdCaptureTest implements FabricClientGameTest {
    /**
     * A non-dense server map id: {@code getFreeMapId} would issue 0 first and on-mode re-keys to a dense 0/1/..., so
     * keeping this exact id verbatim is the off-mode identity proof. Registered directly via {@code
     * setMapData} (the free-id counter is left untouched), which the framed map then references.
     */
    private static final int ORIGINAL_MAP_ID = 1234;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            int frameY = context.computeOnClient(client -> client.player.blockPosition().getY());
            BlockPos frame = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 8);

            // Register and paint the saved data under the chosen non-dense id (setMapData does not consume a free
            // id, so the map keeps exactly this id), so the framed map references a real colored image the server
            // can push to the client rather than a blank array.
            server.runOnServer(minecraftServer -> {
                ServerLevel overworld = minecraftServer.overworld();
                MapId mapId = new MapId(ORIGINAL_MAP_ID);
                overworld.setMapData(mapId,
                        MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, overworld.dimension()));
                MapFixture.paintBands(overworld, mapId);
            });

            Properties offProperties = new Properties();
            offProperties.setProperty("remapMapIds", "false");
            WdlConfig off = WdlConfig.parse(offProperties);

            CaptureDriver run = CaptureDriver.start(context,
                    new DownloadTarget("wdl-offmode-map", "wdl-offmode-map", DownloadMode.NEW), off);
            run.tick(5);

            MapFixture.summonFramedMap(server, frame, ORIGINAL_MAP_ID);
            context.waitFor(client -> ContainerDriver.framedMapCount(client) >= 1
                    && client.level.getMapData(new MapId(ORIGINAL_MAP_ID)) != null);
            Path saveRoot = run.tick(20).stopAndAwaitSave();

            // The original server id is the data-file name verbatim, not densified to 0.
            List<Integer> capturedIds = CaptureReadback.mapDataIds(saveRoot);
            Check.that(capturedIds.equals(List.of(ORIGINAL_MAP_ID)),
                    "off-mode must keep the original server id as data/map_" + ORIGINAL_MAP_ID + ".dat, not re-key "
                            + "it to a dense id; got " + capturedIds);

            // The kept file is the real received image (painted colors round-tripped under the original id), not
            // an empty placeholder at the right name.
            CompoundTag mapImage = CaptureReadback.readDataInner(CaptureReadback.mapDataFiles(saveRoot).get(0));
            Check.that(CaptureReadback.isWellFormedMapImage(mapImage),
                    "the kept map_" + ORIGINAL_MAP_ID + ".dat carries no 128x128 colors array");
            Check.that(CaptureReadback.distinctNonZeroColors(mapImage) >= 3,
                    "the kept map_" + ORIGINAL_MAP_ID + ".dat did not round-trip the painted colors under the "
                            + "original id");

            // Off-mode persists no remap manifest; its absence is the on-disk mode signal a resume reads.
            Check.that(!MapManifest.existsIn(saveRoot),
                    "off-mode must not write a manifest; " + MapManifest.pathIn(saveRoot) + " is present");

            // The captured frame's map_id component still points at the original id (an identity remap, not a rewrite).
            CompoundTag entityChunk = CaptureReadback.readEntityChunk(saveRoot, chunk)
                    .orElseThrow(() -> new AssertionError("the captured entity chunk holding the framed map is gone"));
            int framedId = CaptureReadback.entities(entityChunk).stream()
                    .filter(entity -> entity.getString("id").orElse("").equals("minecraft:item_frame"))
                    .findFirst()
                    .flatMap(itemFrame -> itemFrame.getCompound("Item"))
                    .flatMap(item -> item.getCompound("components"))
                    .flatMap(components -> components.getInt("minecraft:map_id"))
                    .orElseThrow(() -> new AssertionError("the captured item frame carries no minecraft:map_id"));
            Check.that(framedId == ORIGINAL_MAP_ID,
                    "off-mode must leave the framed map's minecraft:map_id at the original " + ORIGINAL_MAP_ID
                            + ", not rewrite it; got " + framedId);
        }
    }
}
