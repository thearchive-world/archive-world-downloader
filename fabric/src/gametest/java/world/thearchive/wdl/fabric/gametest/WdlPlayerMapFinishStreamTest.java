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

@SuppressWarnings("UnstableApiUsage")
public class WdlPlayerMapFinishStreamTest implements FabricClientGameTest {
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
            context.waitFor(client -> client.player.getInventory().contains(stack -> stack.is(Items.FILLED_MAP)));
            context.waitFor(client -> client.level.getMapData(new MapId(HELD_MAP_ID)) != null);

            Path saveRoot = CaptureDriver.capture(context,
                    new DownloadTarget("wdl-player-map-finish", "wdl-player-map-finish", DownloadMode.NEW),
                    WdlConfig.DEFAULTS, 30);

            Check.that(!CaptureReadback.mapDataIds(saveRoot).isEmpty(),
                    "a player-held map is remapped only at finish; the map's data file missing means the finish-time "
                            + "remap ran after the writer's finalize marker and was silently dropped");
        }
    }
}
