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
 * The frame is summoned mid-capture so the inbound entity tee captures it.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlOffModeMapIdCaptureTest implements FabricClientGameTest {
    /**
     * A non-dense server map id: {@code getFreeMapId} would issue 0 first and on-mode re-keys to a dense 0/1/..., so
     * keeping this exact id verbatim is the off-mode identity proof.
     */
    private static final int ORIGINAL_MAP_ID = 1234;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();

            ChunkPos chunk = context.computeOnClient(client -> client.player.chunkPosition());
            int frameY = context.computeOnClient(client -> client.player.blockPosition().getY());
            BlockPos frame = new BlockPos(chunk.getMinBlockX() + 8, frameY, chunk.getMinBlockZ() + 8);

            // Register and paint the saved data under the chosen non-dense id, so the framed map references a real
            // colored image the server can push to the client rather than a blank array.
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

            List<Integer> capturedIds = CaptureReadback.mapDataIds(saveRoot);
            Check.that(capturedIds.equals(List.of(ORIGINAL_MAP_ID)),
                    "off-mode must keep the original server id as data/map_" + ORIGINAL_MAP_ID + ".dat, not re-key "
                            + "it to a dense id; got " + capturedIds);

            CompoundTag mapImage = CaptureReadback.readDataInner(CaptureReadback.mapDataFiles(saveRoot).get(0));
            Check.that(CaptureReadback.isWellFormedMapImage(mapImage),
                    "the kept map_" + ORIGINAL_MAP_ID + ".dat carries no 128x128 colors array");
            Check.that(CaptureReadback.distinctNonZeroColors(mapImage) >= 3,
                    "the kept map_" + ORIGINAL_MAP_ID + ".dat did not round-trip the painted colors under the "
                            + "original id");

            Check.that(!MapManifest.existsIn(saveRoot),
                    "off-mode must not write a manifest; " + MapManifest.pathIn(saveRoot) + " is present");

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
