// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The frozen headline counts a download reached: how many chunks, entities, and containers reached disk, using the
 * dedup-correct semantics, plus the per-dimension chunk breakdown a portal-following download spans. A plain immutable
 * value, frozen at end-of-capture so it never ticks past that moment. The {@link #chunks()} total is the sum of the
 * per-dimension counts.
 */
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

    /** The per-dimension chunk breakdown, in capture order; empty for a record that predates the field. */
    public List<DimensionChunks> dimensions() {
        return dimensions;
    }
}
