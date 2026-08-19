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
        touchRegionFile(saveRoot.resolve("dimensions").resolve("myns").resolve("void").resolve("region"));
        touchRegionFile(saveRoot.resolve("dimensions").resolve("ans").resolve("deep").resolve("mine")
                .resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        List<String> names = new ArrayList<>(directories.keySet());
        assertEquals(ImmutableList.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                "ans:deep/mine", "myns:void"), names, "vanilla first, then customs sorted by name");
        assertEquals(saveRoot.resolve("DIM-1").resolve("region"), directories.get("minecraft:the_nether"));
        assertEquals(saveRoot.resolve("dimensions").resolve("myns").resolve("void").resolve("region"),
                directories.get("myns:void"));
    }

    @Test
    void aDimensionPathLiterallyNamedRegionResolvesToItsRealRegionDir(@TempDir Path saveRoot)
            throws IOException {
        // dimensions/myns/region is the dimension ROOT of a dimension whose path is "region"; its real
        // region directory is dimensions/myns/region/region. The parent holds no region files, so the
        // visitor descends through it instead of matching it, and no phantom entry appears.
        touchRegionFile(saveRoot.resolve("dimensions").resolve("myns").resolve("region").resolve("region"));

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        assertEquals(ImmutableList.of("myns:region"), new ArrayList<>(directories.keySet()));
    }

    @Test
    void skipsAbsentDimensionsAndFileLessCustomCandidatesAndCreatesNothing(@TempDir Path saveRoot)
            throws IOException {
        Files.createDirectories(saveRoot.resolve("region"));
        Files.createDirectories(saveRoot.resolve("dimensions").resolve("myns").resolve("empty")
                .resolve("region")); // custom candidate without region files: not enumerated

        Map<String, Path> directories = new WorldPathsImpl(saveRoot).onDiskRegionDirectories();

        assertEquals(ImmutableList.of("minecraft:overworld"), new ArrayList<>(directories.keySet()));
        assertTrue(Files.notExists(saveRoot.resolve("DIM-1")), "enumeration must not create directories");
    }

    /** The directory plus one empty region-named file, enough for the holds-region-files check. */
    private static void touchRegionFile(Path regionDirectory) throws IOException {
        Files.createDirectories(regionDirectory);
        Files.createFile(regionDirectory.resolve("r.0.0.mca"));
    }
}
