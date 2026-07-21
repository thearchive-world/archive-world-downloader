// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One download as read back from the machine record: its identity, settings diff, and the server/software environment
 * captured at begin, plus the optional completion. A session with no completion reads as interrupted
 * ({@link #isComplete()} is false, {@link #finishedAt()} and {@link #counts()} null). The environment is null only for
 * a pre-bump v1 record that predates it.
 */
public final class DownloadSession {
    private final DownloadIdentity identity;
    private final Map<String, String> settings;
    private final @Nullable ReportEnvironment environment;
    private final boolean complete;
    private final boolean clean;
    private final @Nullable Instant finishedAt;
    private final @Nullable DownloadCounts counts;
    private final @Nullable SaveChunks saveChunks;

    public DownloadSession(DownloadIdentity identity, Map<String, String> settings,
            @Nullable ReportEnvironment environment, boolean complete, boolean clean,
            @Nullable Instant finishedAt, @Nullable DownloadCounts counts, @Nullable SaveChunks saveChunks) {
        this.identity = identity;
        this.settings = settings;
        this.environment = environment;
        this.complete = complete;
        this.clean = clean;
        this.finishedAt = finishedAt;
        this.counts = counts;
        this.saveChunks = saveChunks;
    }

    public DownloadIdentity identity() {
        return identity;
    }

    /** The capture-time settings that differ from the defaults, keyed by config key. */
    public Map<String, String> settings() {
        return settings;
    }

    /** The server/software context captured at begin; null when read from a pre-bump v1 record. */
    public @Nullable ReportEnvironment environment() {
        return environment;
    }

    /** Whether this download wrote a completion record; absence reads as interrupted. */
    public boolean isComplete() {
        return complete;
    }

    /** Whether the completion recorded a clean finish (only meaningful when {@link #isComplete()}). */
    public boolean isClean() {
        return clean;
    }

    public @Nullable Instant finishedAt() {
        return finishedAt;
    }

    public @Nullable DownloadCounts counts() {
        return counts;
    }

    /** The frozen in-save chunk totals (cumulative across sessions); null for an interrupted session. */
    public @Nullable SaveChunks saveChunks() {
        return saveChunks;
    }
}
