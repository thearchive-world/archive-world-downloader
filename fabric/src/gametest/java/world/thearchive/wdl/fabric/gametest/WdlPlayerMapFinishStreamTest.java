// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.WdlConfig;

/**
 * The finish-time streaming invariant: a filled map held only in the player inventory (no frame, no container, nothing
 * that flushes mid-capture) is remapped only by the finish-time player assembly, so its streamed
 * {@code data/map_<id>.dat} write must be enqueued before the writer's finalize marker. A regression that reorders the
 * finish-time player remap after the writer's {@code finish()} silently drops the map's write (a submit after the
 * marker is discarded with no loss tally), and this run turns red on the missing file.
 */
@SuppressWarnings("UnstableApiUsage")
public class WdlPlayerMapFinishStreamTest implements FabricClientGameTest {
    /** The held map's server id, registered directly via {@code setMapData} so the given map references it. */
    private static final int HELD_MAP_ID = 7;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (MultiplayerFixture fixture = MultiplayerFixture.connect(context)) {
            TestServerContext server = fixture.server();
            SaveFixture.reset(context.computeOnClient(client -> client.getLevelSource().getBaseDir()),
                    "wdl-player-map-finish");

            server.runOnServer(minecraftServer -> {
                ServerLevel overworld = minecraftServer.overworld();
                MapId mapId = new MapId(HELD_MAP_ID);
                overworld.setMapData(mapId,
                        MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, overworld.dimension()));
                MapFixture.paintBands(overworld, mapId);
            });
            server.runCommand("give @a minecraft:filled_map[map_id=" + HELD_MAP_ID + "]");
            // The arrival wait doubles as the guard against a silently no-oped give command.
            context.waitFor(client -> client.player.getInventory().contains(stack -> stack.is(Items.FILLED_MAP)));
            // Wait for the colors: the server sends map data for a filled map in any main-inventory slot
            // (ServerPlayer.doTick runs synchronizeSpecialItemUpdates per slot), so the given map's colors
            // arrive without a frame. Without them the resolver returns null and nothing would stream, so
            // this wait is load-bearing.
            context.waitFor(client -> client.level.getMapData(new MapId(HELD_MAP_ID)) != null);

            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-player-map-finish", "wdl-player-map-finish", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, 30);

            Check.that(!CaptureReadback.mapDataIds(saveRoot).isEmpty(),
                    "a player-held map is remapped only at finish; its map_<id>.dat missing means the finish-time "
                            + "remap ran after the writer's finalize marker and was silently dropped");
        }
    }
}
