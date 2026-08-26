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

/**
 * The automated guard for the per-band map serialize: serializeMap turns a client-shaped {@link MapData} into the inner
 * {@code "data"} tag vanilla's own {@code MapData.readFromNBT} reads back to an equal map (the image, dimension, and
 * scale faithful). Map locking is a 1.14 addition absent at this band, so the serialize writes no {@code "locked"} key.
 * The dimension is the primitive int {@code MapData.d} at this band, not a nullable {@code DimensionType}, so there is
 * no version-bridging-proxy null-dimension case to stand in for here. Server-free by construction: a constructed client
 * map drives it, so neither a live menu nor a {@code World} is needed.
 */
class MapSinkRoundTripTest {
    private final MapSink sink = new MapSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    private static MapData clientMap(byte scale) {
        // This band has no MapData.createForClient and no map locking (a 1.14 addition); a client map is the
        // bare instance with its public scale and dimension fields set, the colors array already sized by the field
        // initializer.
        MapData map = new MapData("map");
        map.scale = scale;
        map.d = DimensionType.OVERWORLD.getId();
        // A recognizable non-blank image, so a dropped or zeroed colors array fails the round-trip.
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
        assertFalse(data.hasKey("locked"),
                "map locking is a 1.14 addition, so no locked key is written at this band");
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
