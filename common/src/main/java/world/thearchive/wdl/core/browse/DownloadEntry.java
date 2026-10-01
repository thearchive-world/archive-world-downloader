// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.report.DownloadCounts;

public final class DownloadEntry {
    private final String folderName;
    private final String worldName;
    private final String displayName;
    private final long lastPlayedEpochMillis;
    private final DownloadHealth health;
    private final @Nullable DownloadCounts counts;
    private final byte @Nullable [] iconBytes;
    private final boolean currentlyLoaded;
    private final boolean chunksOnly;
    private final boolean tainted;

    public DownloadEntry(String folderName, String worldName, String displayName, long lastPlayedEpochMillis,
            DownloadHealth health, @Nullable DownloadCounts counts, byte @Nullable [] iconBytes,
            boolean currentlyLoaded, boolean chunksOnly, boolean tainted) {
        this.folderName = folderName;
        this.worldName = worldName;
        this.displayName = displayName;
        this.lastPlayedEpochMillis = lastPlayedEpochMillis;
        this.health = health;
        this.counts = counts;
        this.iconBytes = iconBytes;
        this.currentlyLoaded = currentlyLoaded;
        this.chunksOnly = chunksOnly;
        this.tainted = tainted;
    }

    public String folderName() {
        return folderName;
    }

    public String worldName() {
        return worldName;
    }

    /** The world name with any trailing date suffix stripped. */
    public String displayName() {
        return displayName;
    }

    public long lastPlayedEpochMillis() {
        return lastPlayedEpochMillis;
    }

    public DownloadHealth health() {
        return health;
    }

    /**
     * The capture summary, or null for a recoverable download.
     */
    public @Nullable DownloadCounts counts() {
        return counts;
    }

    /** The validated world-icon bytes, or null when absent or refused. */
    public byte @Nullable [] iconBytes() {
        return iconBytes;
    }

    public boolean isCurrentlyLoaded() {
        return currentlyLoaded;
    }

    public boolean isChunksOnly() {
        return chunksOnly;
    }

    public boolean isTainted() {
        return tainted;
    }
}
