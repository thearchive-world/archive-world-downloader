// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.adapter.impl.MapSinkImpl;
import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The automated guard for the per-band map serialize: serializeMap turns a client-shaped {@link MapItemSavedData} into
 * the inner {@code "data"} tag vanilla's own {@code MapItemSavedData.load} reads back to an equal map (the image,
 * dimension, and scale faithful), and a locked map serializes {@code locked=true}. Server-free by construction: a
 * constructed client map drives it, so neither a live menu nor a {@code Level} is needed; the live {@code getMapData}
 * resolution is not exercised headless.
 */
class MapSinkRoundTripTest {
    private static RegistryAccess registries;
    private final MapSink sink = new MapSinkImpl();

    @BeforeAll
    static void bootstrapVanilla() {
        registries = TestRegistries.frozen();
    }

    private static MapItemSavedData clientMap(byte scale, boolean locked) {
        // 1.16.5 has no MapItemSavedData.createForClient; a client map is the bare instance with its public
        // scale/dimension/locked fields set, the colors array already sized by the field initializer.
        MapItemSavedData map = new MapItemSavedData("map");
        map.scale = scale;
        map.dimension = Level.OVERWORLD;
        map.locked = locked;
        // A recognizable non-blank image, so a dropped or zeroed colors array fails the round-trip.
        map.colors[0] = (byte) 42;
        map.colors[100] = (byte) -7;
        map.colors[16383] = (byte) 99;
        return map;
    }

    private MapItemSavedData roundTrip(MapItemSavedData map) {
        Tag data = sink.serializeMap(map, registries);
        MapItemSavedData back = new MapItemSavedData("map");
        back.load((CompoundTag) data);
        return back;
    }

    @Test
    void serializeMapPreservesTheImageDimensionAndScale() {
        MapItemSavedData map = clientMap((byte) 2, false);

        MapItemSavedData back = roundTrip(map);

        assertArrayEquals(map.colors, back.colors, "the 128x128 image survives the serialize");
        assertEquals(map.dimension, back.dimension, "the dimension survives");
        assertEquals(map.scale, back.scale, "the scale survives");
        assertFalse(back.locked, "an unlocked map serializes locked=false");
    }

    @Test
    void aLockedMapSerializesLockedWithItsImage() {
        MapItemSavedData map = clientMap((byte) 0, true);

        MapItemSavedData back = roundTrip(map);

        assertTrue(back.locked, "a locked map serializes locked=true (auto-lock)");
        assertEquals((byte) 42, back.colors[0], "the locked map's image survives the serialize");
    }

    @Test
    void aDimensionlessMapStillSerializesItsImage() {
        // A version-bridging proxy (ViaVersion/ViaBackwards fronting a newer server) can relay a filled map to the
        // client with a null dimension, which vanilla save dereferences; the serialize must stand one in rather
        // than lose the image, and leave the live client map's dimension as it was received.
        MapItemSavedData map = clientMap((byte) 1, false);
        map.dimension = null;

        MapItemSavedData back = roundTrip(map);

        assertArrayEquals(map.colors, back.colors, "the image survives a dimensionless serialize");
        assertEquals(Level.OVERWORLD, back.dimension, "the serialize stands in the overworld key");
        assertNull(map.dimension, "the live client map is left with its null dimension");
    }
}
