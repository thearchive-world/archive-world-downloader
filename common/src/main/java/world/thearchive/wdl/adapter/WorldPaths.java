// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;

/**
 * Per-band save-layout axis: maps a dimension to its on-disk {@code region/} and {@code entities/} directories and
 * opens the vanilla region storage rooted at each.
 *
 * <p>The per-band implementation pre-creates the {@code region/} and {@code entities/} directories before any region
 * write.
 */
public interface WorldPaths {
    Path regionDirectory(ResourceKey<Level> dimension);

    Path entitiesDirectory(ResourceKey<Level> dimension);

    IOWorker openRegionStorage(ResourceKey<Level> dimension);

    /** Like {@link #openRegionStorage} but rooted at the {@code entities/} region. */
    IOWorker openEntitiesStorage(ResourceKey<Level> dimension);

    /**
     * The global {@code data/} directory (the SavedData surface: the map files and the map-id index), which lives under
     * the save root regardless of dimension. Resolve-only, not pre-created: whichever writer reaches it first
     * {@code createDirectories} it, so a session that captures no maps leaves no empty {@code data/} directory.
     */
    Path dataDirectory();

    /**
     * The dimension {@code region/} directories present on disk under the save root, keyed by dimension name (for
     * example {@code minecraft:overworld}), for the completion-time save-total scan. Read-only: unlike
     * {@link #regionDirectory} it never creates a directory, and it includes dimensions this session never visited. A
     * custom-dimension candidate is listed only when it holds region files; the vanilla directories are listed on
     * existence. The walked layout is per-band implementation knowledge, like the forward mapping.
     */
    Map<String, Path> onDiskRegionDirectories();
}
