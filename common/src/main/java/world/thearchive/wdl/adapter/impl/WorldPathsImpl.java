// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

import world.thearchive.wdl.adapter.WorldPaths;

public final class WorldPathsImpl implements WorldPaths {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path saveRoot;

    public WorldPathsImpl(Path saveRoot) {
        this.saveRoot = saveRoot;
    }

    @Override
    public Path regionDirectory(ResourceKey<Level> dimension) {
        return ensureDirectory(dimensionRoot(dimension).resolve("region"));
    }

    @Override
    public Path entitiesDirectory(ResourceKey<Level> dimension) {
        return ensureDirectory(dimensionRoot(dimension).resolve("entities"));
    }

    @Override
    public RegionStorageInfo regionStorageInfo(ResourceKey<Level> dimension) {
        return storageInfo(dimension, "chunk");
    }

    @Override
    public RegionStorageInfo entitiesStorageInfo(ResourceKey<Level> dimension) {
        return storageInfo(dimension, "entities");
    }

    private RegionStorageInfo storageInfo(ResourceKey<Level> dimension, String type) {
        return new RegionStorageInfo(Objects.toString(saveRoot.getFileName(), "wdl"), dimension, type);
    }

    @Override
    public Path dataDirectory() {
        // Maps land at data/minecraft/maps/<id>.dat (vanilla MapId.key() supplies the maps/ leg).
        return saveRoot.resolve("data").resolve("minecraft");
    }

    @Override
    public Map<String, Path> onDiskRegionDirectories() {
        Map<String, Path> result = new LinkedHashMap<>();
        putIfPresent(result, "minecraft:overworld", saveRoot.resolve("region"));
        putIfPresent(result, "minecraft:the_nether", saveRoot.resolve("DIM-1").resolve("region"));
        putIfPresent(result, "minecraft:the_end", saveRoot.resolve("DIM1").resolve("region"));
        Path dimensions = saveRoot.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            // The holds-check lets a dimension whose path is literally "region" resolve to its real region directory
            // one level deeper instead of its own root.
            Map<String, Path> custom = new TreeMap<>();
            try {
                Files.walkFileTree(dimensions, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        Path parent = directory.getParent();
                        if (parent == null || !directory.getFileName().toString().equals("region")
                                || !holdsRegionFiles(directory)) {
                            return FileVisitResult.CONTINUE;
                        }
                        custom.put(dimensionName(dimensions, parent), directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception) {
                        LOGGER.warn("skipping an unreadable entry under {}; save totals may be partial",
                                dimensions, exception);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("cannot walk the dimensions directory {}; save totals will be partial",
                        dimensions, e);
            }
            result.putAll(custom);
        }
        return result;
    }

    private static void putIfPresent(Map<String, Path> result, String name, Path regionDirectory) {
        if (Files.isDirectory(regionDirectory)) {
            result.put(name, regionDirectory);
        }
    }

    private static boolean holdsRegionFiles(Path directory) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "r.*.mca")) {
            return files.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static String dimensionName(Path dimensions, Path dimensionRoot) {
        String relative = dimensions.relativize(dimensionRoot).toString().replace('\\', '/');
        int slash = relative.indexOf('/');
        return slash < 0 ? relative : relative.substring(0, slash) + ':' + relative.substring(slash + 1);
    }

    private Path dimensionRoot(ResourceKey<Level> dimension) {
        return DimensionType.getStorageFolder(dimension, saveRoot);
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
