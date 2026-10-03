// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

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
