// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The automated guard for the per-band map serialize (the 1.21.5 SavedData to Codec seam): serializeMap turns a
 * client-shaped {@link MapItemSavedData} into the inner {@code "data"} tag vanilla's own {@code MapItemSavedData.CODEC}
 * reads back to an equal map (the image, dimension, and scale faithful), and the auto-lock snapshot
 * ({@code saved.locked()}) serializes {@code locked=true} over an independent image copy. Server-free by construction:
 * a constructed client map drives it, so neither a live menu nor a {@code Level} is needed; the live {@code getMapData}
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
        MapItemSavedData map = MapItemSavedData.createForClient(scale, locked, Level.OVERWORLD);
        // A recognizable non-blank image, so a dropped or zeroed colors array fails the round-trip.
        map.colors[0] = (byte) 42;
        map.colors[100] = (byte) -7;
        map.colors[16383] = (byte) 99;
        return map;
    }

    private MapItemSavedData roundTrip(MapItemSavedData map) {
        Tag data = sink.serializeMap(map, registries);
        return MapItemSavedData.load((CompoundTag) data);
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
    void lockedSnapshotSerializesLockedOverAnIndependentImage() {
        MapItemSavedData map = clientMap((byte) 0, false);
        MapItemSavedData locked = map.locked();
        // Mutate the source after snapshotting: the locked() copy must be independent (the writer-thread hand-off).
        map.colors[0] = (byte) 1;

        MapItemSavedData back = roundTrip(locked);

        assertTrue(back.locked, "the locked snapshot serializes locked=true (auto-lock)");
        assertEquals((byte) 42, back.colors[0],
                "locked() arraycopied the image, so the later source mutation does not leak into the snapshot");
    }

    @Test
    void theSerializedTagDoesNotAliasTheLiveColors() {
        MapItemSavedData map = clientMap((byte) 0, false);

        CompoundTag data = (CompoundTag) sink.serializeMap(map, registries);
        data.getByteArray("colors")[0] = (byte) 7;

        assertEquals((byte) 42, map.colors[0],
                "the live client map keeps its image when the tag handed to the writer thread is written to");
    }
}
