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

/**
 * 1.21.3 save-layout axis. Rooted at a single world save directory; maps a dimension to its vanilla on-disk folders and
 * pre-creates {@code region/} + {@code entities/} so the region writer never sees a missing {@code externalFileDir}
 * (vanilla {@code RegionFile} throws otherwise).
 */
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
        // Vanilla EntityStorage uses the "entities" type string for the entities/ region.
        return storageInfo(dimension, "entities");
    }

    private RegionStorageInfo storageInfo(ResourceKey<Level> dimension, String type) {
        // level and type are cosmetic crash-report strings; the real dimension key is load-bearing.
        return new RegionStorageInfo(Objects.toString(saveRoot.getFileName(), "wdl"), dimension, type);
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
        putIfPresent(result, "minecraft:overworld", saveRoot.resolve("region"));
        putIfPresent(result, "minecraft:the_nether", saveRoot.resolve("DIM-1").resolve("region"));
        putIfPresent(result, "minecraft:the_end", saveRoot.resolve("DIM1").resolve("region"));
        Path dimensions = saveRoot.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            // dimensions/<namespace>/<path>/region; a ResourceLocation path may nest further, so any
            // region-file-holding directory named region under the tree is taken, named by its parent's
            // relative path. The holds-check lets a dimension whose path is literally "region" resolve to
            // its real region directory one level deeper instead of its own root.
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
                // Nothing thrown here may escape: a scan problem degrades the totals, never the save.
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

    /** Vanilla layout: overworld at the save root, Nether=DIM-1, End=DIM1, custom={@code dimensions/<ns>/<path>}. */
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
