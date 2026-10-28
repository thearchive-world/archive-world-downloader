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

class WorldPathsImplTest {
    @Test
    void enumeratesTheOnDiskRegionDirectoriesInStableOrder(@TempDir Path saveRoot) throws IOException {
        Files.createDirectories(saveRoot.resolve("region"));
        Files.createDirectories(saveRoot.resolve("DIM-1").resolve("region"));
        Files.createDirectories(saveRoot.resolve("DIM1").resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        List<String> names = new ArrayList<>(directories.keySet());
        assertEquals(ImmutableList.of("overworld", "the_nether", "the_end"), names,
                "the fixed vanilla dimension set, in DimensionType id order");
        assertEquals(saveRoot.resolve("DIM-1").resolve("region"), directories.get("the_nether"));
    }

    @Test
    void skipsAbsentDimensionsAndCreatesNothing(@TempDir Path saveRoot) throws IOException {
        Files.createDirectories(saveRoot.resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        assertEquals(ImmutableList.of("overworld"), new ArrayList<>(directories.keySet()));
        assertTrue(Files.notExists(saveRoot.resolve("DIM-1")), "enumeration must not create directories");
    }
}
