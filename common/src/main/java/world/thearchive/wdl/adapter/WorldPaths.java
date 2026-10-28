// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * The per-band implementation pre-creates the {@code region/} and {@code entities/} directories before any region
 * write.
 */
public interface WorldPaths {
    Path regionDirectory(ResourceKey<Level> dimension);

    Path entitiesDirectory(ResourceKey<Level> dimension);

    IOWorker openRegionStorage(ResourceKey<Level> dimension);

    /** Like {@link #openRegionStorage} but rooted at the {@code entities/} region. */
    IOWorker openEntitiesStorage(ResourceKey<Level> dimension);

    /**
     * Resolve-only, not pre-created: whichever writer reaches it first {@code createDirectories} it.
     */
    Path dataDirectory();

    /**
     * The dimension {@code region/} directories present on disk under the save root, keyed by dimension name (for
     * example {@code minecraft:overworld}). Read-only: unlike {@link #regionDirectory} it never creates a directory,
     * and it includes dimensions this session never visited.
     */
    Map<String, Path> onDiskRegionDirectories();
}
