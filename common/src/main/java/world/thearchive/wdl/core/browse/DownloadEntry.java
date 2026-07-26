// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.report.DownloadCounts;

/**
 * One row of the download screen: a wdl-managed download read from disk, MC-free. The summary ({@link #counts}) is
 * present for a {@link DownloadHealth#COMPLETE} or {@link DownloadHealth#PARTIAL} download (a
 * {@link DownloadHealth#RECOVERABLE} one has none); the row's size is not held here, since the screen walks the folder
 * on open. {@link #iconBytes} is the validated icon or null. {@link #currentlyLoaded} marks the currently-open world,
 * which is refused as a target.
 */
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

    /** The on-disk save-folder name (used verbatim as a resume target, and shown in the row tooltip). */
    public String folderName() {
        return folderName;
    }

    /** The world's full {@code level.dat} name (the dated name), prefilled into the field when the row is picked. */
    public String worldName() {
        return worldName;
    }

    /** The row label: the world name with any trailing date suffix stripped for a clean display. */
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
     * The capture summary, or null for a recoverable download (no trustworthy summary); on a resumed row
     * ({@link #isChunksOnly()}) it carries the cumulative chunk total with its entity and container counts not
     * applicable.
     */
    public @Nullable DownloadCounts counts() {
        return counts;
    }

    /** The validated world-icon bytes, or null when absent or refused. */
    public byte @Nullable [] iconBytes() {
        return iconBytes;
    }

    /** Whether this is the currently-loaded world, refused as a download target. */
    public boolean isCurrentlyLoaded() {
        return currentlyLoaded;
    }

    /** Whether the row shows the cumulative in-save chunk total only, with entities and containers not applicable. */
    public boolean isChunksOnly() {
        return chunksOnly;
    }

    /** Whether this download was opened in singleplayer, so resuming into it is refused (or confirmed). */
    public boolean isTainted() {
        return tainted;
    }
}
