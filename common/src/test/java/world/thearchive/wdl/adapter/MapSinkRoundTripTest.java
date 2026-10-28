// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.DimensionType;
import net.minecraft.world.storage.MapData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.MapSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

class MapSinkRoundTripTest {
    private final MapSink sink = new MapSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static MapData clientMap(byte scale) {
        MapData map = new MapData("map");
        map.scale = scale;
        map.d = DimensionType.OVERWORLD.getId();
        map.colors[0] = (byte) 42;
        map.colors[100] = (byte) -7;
        map.colors[16383] = (byte) 99;
        return map;
    }

    @Test
    void serializeMapPreservesTheImageDimensionAndScale() {
        MapData map = clientMap((byte) 2);

        NBTTagCompound data = (NBTTagCompound) sink.serializeMap(map);
        MapData back = new MapData("map");
        back.readFromNBT(data);

        assertArrayEquals(map.colors, back.colors, "the 128x128 image survives the serialize");
        assertEquals(map.d, back.d, "the dimension survives");
        assertEquals(map.scale, back.scale, "the scale survives");
        assertFalse(data.hasKey("locked"), "map locking is a 1.14 addition, so no locked key is written at this band");
    }

    @Test
    void aMapSerializeWritesNoLockedKeyWithItsImage() {
        MapData map = clientMap((byte) 0);

        NBTTagCompound data = (NBTTagCompound) sink.serializeMap(map);
        MapData back = new MapData("map");
        back.readFromNBT(data);

        assertFalse(data.hasKey("locked"),
                "no map locking at this band, so the serialize writes no locked key the newer-band auto-lock would");
        assertEquals((byte) 42, back.colors[0], "the map's image survives the serialize");
    }

    @Test
    void theSerializedTagDoesNotAliasTheLiveColors() {
        MapData map = clientMap((byte) 0);

        NBTTagCompound data = (NBTTagCompound) sink.serializeMap(map);
        data.getByteArray("colors")[0] = (byte) 7;

        assertEquals((byte) 42, map.colors[0],
                "the live client map keeps its image when the tag handed to the writer thread is written to");
    }
}
