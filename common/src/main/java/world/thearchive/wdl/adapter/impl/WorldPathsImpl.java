// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import world.thearchive.wdl.adapter.WorldPaths;

/**
 * 1.15.2 save-layout axis. Rooted at a single world save directory; maps a dimension to its vanilla on-disk folders and
 * pre-creates {@code region/} so the region writer never sees a missing directory (vanilla {@code RegionFile} throws
 * otherwise). There is no {@code entities/} region at this band: entities live inside the {@code region/} chunk under
 * {@code Level.Entities}.
 */
public final class WorldPathsImpl implements WorldPaths {
    private static final Logger LOGGER = LogManager.getLogger(WorldPathsImpl.class);

    private final Path saveRoot;

    public WorldPathsImpl(Path saveRoot) {
        this.saveRoot = saveRoot;
    }

    @Override
    public Path regionDirectory(DimensionType dimension) {
        return ensureDirectory(dimensionRoot(dimension).resolve("region"));
    }

    @Override
    public IOWorker openRegionStorage(DimensionType dimension) {
        // The 1.15.2 IOWorker and RegionFileStorage constructors are package-private, and this shared module has no
        // access widener or transformer (those are per-loader), so the storage is opened reflectively. A same-package
        // shim compiles here but throws IllegalAccessError once the loader remaps the vanilla classes into a different
        // runtime package; reflection with setAccessible is package-independent and is paid once per dimension.
        File directory = regionDirectory(dimension).toFile();
        try {
            Constructor<RegionFileStorage> storageConstructor = RegionFileStorage.class
                    .getDeclaredConstructor(File.class);
            storageConstructor.setAccessible(true);
            Constructor<IOWorker> workerConstructor = IOWorker.class
                    .getDeclaredConstructor(RegionFileStorage.class, String.class);
            workerConstructor.setAccessible(true);
            return workerConstructor.newInstance(storageConstructor.newInstance(directory), "chunk");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to open region storage at " + directory, e);
        }
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

    /** Vanilla layout: overworld at the save root, Nether=DIM-1, End=DIM1. */
    private Path dimensionRoot(DimensionType dimension) {
        return dimension.getStorageFolder(saveRoot.toFile()).toPath();
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
