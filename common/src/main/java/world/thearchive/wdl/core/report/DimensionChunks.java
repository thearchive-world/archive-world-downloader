// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

/**
 * One dimension's chunk count for a report's per-dimension breakdown: the dimension's name ({@code namespace:path},
 * e.g. {@code minecraft:overworld} or {@code minecraft:the_nether}) and how many distinct chunks it holds. A plain
 * immutable value, built one per dimension for both the session breakdown and the in-save scan.
 */
public final class DimensionChunks {
    private final String dimensionName;
    private final int chunks;

    public DimensionChunks(String dimensionName, int chunks) {
        this.dimensionName = dimensionName;
        this.chunks = chunks;
    }

    public String dimensionName() {
        return dimensionName;
    }

    public int chunks() {
        return chunks;
    }
}
