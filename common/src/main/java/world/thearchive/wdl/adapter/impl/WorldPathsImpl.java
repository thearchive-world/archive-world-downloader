// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.DimensionType;

import world.thearchive.wdl.adapter.WdlRegionStorage;
import world.thearchive.wdl.adapter.WorldPaths;

/**
 * 1.11.2 save-layout axis. Rooted at a single world save directory; maps a dimension to its vanilla on-disk folder and
 * pre-creates {@code region/} before the region writer opens it. There is no {@code entities/} region at this band:
 * entities live inside the {@code region/} chunk under {@code Level.Entities}.
 *
 * <p>The dimension folder matches vanilla {@code WorldProvider.getSaveFolder()}: the overworld ({@code DimensionType}
 * id 0) saves at the world root, and every other dimension saves under {@code "DIM" + id} (the fixed {@code DIM-1}/
 * {@code DIM1} folders for the Nether and the End). Below 1.16 there is no per-dimension {@code dimensions/}
 * resource-location tree to scan; the dimensions are the fixed, {@link DimensionType}-registered set.
 */
public final class WorldPathsImpl implements WorldPaths {
    private final Path saveRoot;

    public WorldPathsImpl(Path saveRoot) {
        this.saveRoot = saveRoot;
    }

    @Override
    public Path regionDirectory(DimensionType dimension) {
        return ensureDirectory(dimensionRoot(dimension).resolve("region"));
    }

    @Override
    public WdlRegionStorage openRegionStorage(DimensionType dimension) {
        return new WdlRegionStorage(regionDirectory(dimension).toFile());
    }

    @Override
    public Path dataDirectory() {
        // Global: maps are stored under the overworld save root (= this saveRoot, the overworld dimension
        // path), not under a per-dimension folder. Resolve-only; the first writer to reach it creates it.
        return saveRoot.resolve("data");
    }

    @Override
    public Map<String, Path> onDiskRegionDirectories() {
        Map<String, Path> result = new LinkedHashMap<>();
        for (DimensionType dimension : DimensionType.values()) {
            putIfPresent(result, dimension.getName(), dimensionRoot(dimension).resolve("region"));
        }
        return result;
    }

    private static void putIfPresent(Map<String, Path> result, String name, Path regionDirectory) {
        if (Files.isDirectory(regionDirectory)) {
            result.put(name, regionDirectory);
        }
    }

    /** Vanilla layout: overworld at the save root, every other dimension at {@code "DIM" + getId()}. */
    private Path dimensionRoot(DimensionType dimension) {
        int id = dimension.getId();
        return id == 0 ? saveRoot : saveRoot.resolve("DIM" + id);
    }

    private static Path ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create " + directory, e);
        }
    }
}
