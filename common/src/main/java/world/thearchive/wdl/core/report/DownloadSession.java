// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class DownloadSession {
    private final DownloadIdentity identity;
    private final Map<String, String> settings;
    private final @Nullable ReportEnvironment environment;
    private final boolean complete;
    private final boolean clean;
    private final @Nullable Instant finishedAt;
    private final @Nullable DownloadCounts counts;
    private final @Nullable SaveChunks saveChunks;
    private final Map<String, Integer> losses;

    public DownloadSession(DownloadIdentity identity, Map<String, String> settings,
            @Nullable ReportEnvironment environment, boolean complete, boolean clean,
            @Nullable Instant finishedAt, @Nullable DownloadCounts counts, @Nullable SaveChunks saveChunks) {
        this(identity, settings, environment, complete, clean, finishedAt, counts, saveChunks,
                Collections.emptyMap());
    }

    public DownloadSession(DownloadIdentity identity, Map<String, String> settings,
            @Nullable ReportEnvironment environment, boolean complete, boolean clean,
            @Nullable Instant finishedAt, @Nullable DownloadCounts counts, @Nullable SaveChunks saveChunks,
            Map<String, Integer> losses) {
        this.losses = losses;
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

    public Map<String, String> settings() {
        return settings;
    }

    public @Nullable ReportEnvironment environment() {
        return environment;
    }

    public Map<String, Integer> losses() {
        return losses;
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
