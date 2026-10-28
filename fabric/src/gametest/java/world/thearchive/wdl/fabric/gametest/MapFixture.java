// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Paints a created map with content so a captured map proves the live loop carried the color bytes to disk, not merely
 * the 128x128 array shape.
 */
@SuppressWarnings("UnstableApiUsage")
final class MapFixture {
    private static final MapColor[] bands = { MapColor.GRASS, MapColor.FIRE, MapColor.WATER, MapColor.SNOW };

    private MapFixture() {}

    static void summonFramedMap(TestServerContext server, BlockPos pos, int mapId) {
        // Hang the frame on a constructed wall (a block to its east, facing west): an item frame needs a solid block
        // behind it.
        server.runCommand("setblock " + (pos.getX() + 1) + " " + pos.getY() + " " + pos.getZ() + " minecraft:stone");
        server.runCommand("summon minecraft:item_frame " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " {Facing:4b,Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":"
                + mapId + "}}}");
    }

    static void paintBands(ServerLevel level, MapId mapId) {
        MapItemSavedData data = level.getMapData(mapId);
        if (data == null) {
            throw new AssertionError("map " + mapId + " has no saved data to paint");
        }
        for (int z = 0; z < 128; z++) {
            byte color = bands[z * bands.length / 128].getPackedId(MapColor.Brightness.NORMAL);
            for (int x = 0; x < 128; x++) {
                data.setColor(x, z, color);
            }
        }
    }
}
