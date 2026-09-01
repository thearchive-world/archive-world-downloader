// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Below 1.16 there is no {@code dimensions/<namespace>/<path>} resource-location tree to scan (see
 * {@link WorldPathsImpl}): every dimension is the fixed {@link net.minecraft.world.DimensionType}-registered set, saved
 * at the world root (overworld) or a {@code "DIM" + id} folder. So this pins only that fixed set, keyed by
 * {@code DimensionType.getName()} (no namespace prefix at this band); a custom-namespaced dimension folder is not a
 * scenario this band can construct.
 */
class WorldPathsImplTest {
    @Test
    void enumeratesTheOnDiskRegionDirectoriesInStableOrder(@TempDir Path saveRoot) throws IOException {
        Files.createDirectories(saveRoot.resolve("region"));
        Files.createDirectories(saveRoot.resolve("DIM-1").resolve("region"));
        Files.createDirectories(saveRoot.resolve("DIM1").resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        List<String> names = new ArrayList<>(directories.keySet());
        // DimensionType.getName() returns the display name at this band, not the lowercase id the 1.12 rename
        // introduced. The key is internal, and the on-disk layout is DIM plus the numeric id either way, so only the
        // spelling moves; the folder assertion below is what pins the layout.
        assertEquals(ImmutableList.of("Overworld", "Nether", "The End"), names,
                "the fixed vanilla dimension set, in DimensionType id order");
        assertEquals(saveRoot.resolve("DIM-1").resolve("region"), directories.get("Nether"));
    }

    @Test
    void skipsAbsentDimensionsAndCreatesNothing(@TempDir Path saveRoot) throws IOException {
        Files.createDirectories(saveRoot.resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        assertEquals(ImmutableList.of("Overworld"), new ArrayList<>(directories.keySet()));
        assertTrue(Files.notExists(saveRoot.resolve("DIM-1")), "enumeration must not create directories");
    }
}
