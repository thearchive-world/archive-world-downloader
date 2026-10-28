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
 * Pre-creates {@code region/} before the region writer opens it.
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
