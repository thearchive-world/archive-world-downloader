// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * A live snapshot of capture progress, read by the status command and HUD. MC-free (plain ints) so it crosses the
 * {@link CaptureController.Session} seam without leaking an MC type. The entity tally is the live per-UUID count
 * accumulated during capture, dedup-correct (each entity counted once by UUID), not the finish-time entity-chunk region
 * tags.
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
