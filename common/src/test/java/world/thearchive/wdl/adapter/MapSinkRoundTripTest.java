// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
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

    private static MapItemSavedData clientMap(byte scale) {
        MapItemSavedData map = new MapItemSavedData("map");
        map.scale = scale;
        map.dimension = DimensionType.field_18954;
        map.colors[0] = (byte) 42;
        map.colors[100] = (byte) -7;
        map.colors[16383] = (byte) 99;
        return map;
    }

    private MapItemSavedData roundTrip(MapItemSavedData map) {
        Tag data = sink.serializeMap(map);
        MapItemSavedData back = new MapItemSavedData("map");
        back.load((CompoundTag) data);
        return back;
    }

    @Test
    void serializeMapPreservesTheImageDimensionAndScale() {
        MapItemSavedData map = clientMap((byte) 2);

        CompoundTag data = (CompoundTag) sink.serializeMap(map);
        MapItemSavedData back = new MapItemSavedData("map");
        back.load(data);

        assertArrayEquals(map.colors, back.colors, "the 128x128 image survives the serialize");
        assertEquals(map.dimension, back.dimension, "the dimension survives");
        assertEquals(map.scale, back.scale, "the scale survives");
        assertFalse(data.contains("locked"),
                "map locking is a 1.14 addition, so no locked key is written at this band");
    }

    @Test
    void aMapSerializeWritesNoLockedKeyWithItsImage() {
        MapItemSavedData map = clientMap((byte) 0);

        CompoundTag data = (CompoundTag) sink.serializeMap(map);
        MapItemSavedData back = new MapItemSavedData("map");
        back.load(data);

        assertFalse(data.contains("locked"),
                "no map locking at this band, so the serialize writes no locked key the newer-band auto-lock would");
        assertEquals((byte) 42, back.colors[0], "the map's image survives the serialize");
    }

    @Test
    void aDimensionlessMapStillSerializesItsImage() {
        // A filled map can reach the client with a null dimension, which vanilla save dereferences.
        MapItemSavedData map = clientMap((byte) 1);
        map.dimension = null;

        MapItemSavedData back = roundTrip(map);

        assertArrayEquals(map.colors, back.colors, "the image survives a dimensionless serialize");
        assertEquals(DimensionType.field_18954, back.dimension, "the serialize stands in the overworld key");
        assertNull(map.dimension, "the live client map is left with its null dimension");
    }

    @Test
    void theSerializedTagDoesNotAliasTheLiveColors() {
        MapItemSavedData map = clientMap((byte) 0);

        CompoundTag data = (CompoundTag) sink.serializeMap(map);
        data.getByteArray("colors")[0] = (byte) 7;

        assertEquals((byte) 42, map.colors[0],
                "the live client map keeps its image when the tag handed to the writer thread is written to");
    }

    @Test
    void theDimensionlessSerializeDoesNotAliasTheLiveColorsEither() {
        // The null-dimension stand-in is a second serialize return, which a fixture carrying a dimension never reaches.
        MapItemSavedData map = clientMap((byte) 0);
        map.dimension = null;

        CompoundTag data = (CompoundTag) sink.serializeMap(map);
        data.getByteArray("colors")[0] = (byte) 7;

        assertEquals((byte) 42, map.colors[0],
                "the proxy-relayed map keeps its image when the tag handed to the writer thread is written to");
    }
}
