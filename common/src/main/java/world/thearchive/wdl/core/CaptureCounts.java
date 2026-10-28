// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * A snapshot of capture progress. The entity tally counts each entity once, by UUID.
 */
public final class CaptureCounts {
    /** No capture in progress. */
    public static final CaptureCounts EMPTY = new CaptureCounts(0, 0, 0);

    private final int chunks;
    private final int containers;
    private final int entities;

    public CaptureCounts(int chunks, int containers, int entities) {
        this.chunks = chunks;
        this.containers = containers;
        this.entities = entities;
    }

    public int chunks() {
        return chunks;
    }

    public int containers() {
        return containers;
    }

    public int entities() {
        return entities;
    }
}
