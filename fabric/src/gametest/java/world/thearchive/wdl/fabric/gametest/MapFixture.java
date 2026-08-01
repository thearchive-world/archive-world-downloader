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
 * the 128x128 array shape, and is visually verifiable when the save is opened: a blank map (all zeroes) and a corrupt
 * one read alike, but a few distinct bands do not. The bands use the oldest basic color values (band-stable since early
 * versions, though the {@code MapColor} and {@code MapId} API itself floors at 1.20.5), and the readback
 * ({@link CaptureReadback#distinctNonZeroColors}) counts distinct colors rather than pinning palette bytes, which
 * change between versions. Field-level pixel fidelity stays the unit suite's ({@code MapArchiveTest},
 * {@code MapSinkRoundTripTest}).
 */
@SuppressWarnings("UnstableApiUsage")
final class MapFixture {
    /** Four basic colors present on every band (green, red, blue, white), painted as horizontal bands. */
    private static final MapColor[] bands = { MapColor.GRASS, MapColor.FIRE, MapColor.WATER, MapColor.SNOW };

    private MapFixture() {}

    /**
     * Summon a framed map at {@code pos} holding {@code filled_map[map_id=mapId]}, hung on a wall block to its east.
     */
    static void summonFramedMap(TestServerContext server, BlockPos pos, int mapId) {
        // Hang the frame on a constructed wall (a block to its east, facing west): an item frame needs a solid
        // block behind it, and older bands place frames only on a vertical face, never the floor or ceiling.
        server.runCommand("setblock " + (pos.getX() + 1) + " " + pos.getY() + " " + pos.getZ() + " minecraft:stone");
        server.runCommand("summon minecraft:item_frame " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " {Facing:4b,Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":"
                + mapId + "}}}");
    }

    /** Fill {@code mapId}'s image with horizontal color bands; the map must already carry saved data. */
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
