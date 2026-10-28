// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownloadCounts {
    private final int chunks;
    private final int entities;
    private final int containers;
    private final List<DimensionChunks> dimensions;

    public DownloadCounts(int chunks, int entities, int containers) {
        this(chunks, entities, containers, Collections.<DimensionChunks>emptyList());
    }

    public DownloadCounts(int chunks, int entities, int containers,
            List<DimensionChunks> dimensions) {
        this.chunks = chunks;
        this.entities = entities;
        this.containers = containers;
        this.dimensions = Collections.unmodifiableList(new ArrayList<>(dimensions));
    }

    public int chunks() {
        return chunks;
    }

    public int entities() {
        return entities;
    }

    public int containers() {
        return containers;
    }

    public List<DimensionChunks> dimensions() {
        return dimensions;
    }
}
