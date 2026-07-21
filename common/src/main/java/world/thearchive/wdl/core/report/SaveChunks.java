// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import world.thearchive.wdl.core.RegionChunkScan;

/**
 * The frozen in-save chunk totals a completed download recorded: every chunk present on disk at the finish instant, per
 * dimension, from the region-header scan. Unlike the session counts this covers prior sessions and dimensions the
 * session never visited. A plain immutable value; {@link #total()} is the sum of the per-dimension counts.
 */
public final class SaveChunks {
    private final int total;
    private final List<DimensionChunks> dimensions;

    public SaveChunks(int total, List<DimensionChunks> dimensions) {
        this.total = total;
        this.dimensions = Collections.unmodifiableList(new ArrayList<>(dimensions));
    }

    /**
     * Scan the region headers of {@code regionDirectories} (dimension name to its {@code region/} directory, insertion
     * order preserved), keeping only dimensions with at least one chunk, so a created-but-empty region directory never
     * pads the breakdown.
     */
    public static SaveChunks scan(Map<String, Path> regionDirectories) {
        List<DimensionChunks> dimensions = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Path> dimension : regionDirectories.entrySet()) {
            int chunks = RegionChunkScan.presentChunks(dimension.getValue()).length;
            if (chunks > 0) {
                dimensions.add(new DimensionChunks(dimension.getKey(), chunks));
                total += chunks;
            }
        }
        return new SaveChunks(total, dimensions);
    }

    public int total() {
        return total;
    }

    /** The per-dimension breakdown, one entry per dimension with at least one chunk on disk. */
    public List<DimensionChunks> dimensions() {
        return dimensions;
    }
}
