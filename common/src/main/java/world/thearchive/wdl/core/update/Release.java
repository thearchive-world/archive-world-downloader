// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One immutable release row of the index. Constructed only from a fully-parsed row with every required field present
 * (the parse boundary drops partial rows), so the decision never sees a partial release; final fields only, so a row
 * publishes safely across the worker thread boundary.
 */
public final class Release {
    private final String versionNumber;
    private final SemVer version;
    private final List<String> loaders;
    private final List<String> gameVersions;
    private final Instant datePublished;
    private final String status;
    private final String versionType;

    public Release(String versionNumber, SemVer version, List<String> loaders, List<String> gameVersions,
            Instant datePublished, String status, String versionType) {
        this.versionNumber = versionNumber;
        this.version = version;
        this.loaders = Collections.unmodifiableList(new ArrayList<String>(loaders));
        this.gameVersions = Collections.unmodifiableList(new ArrayList<String>(gameVersions));
        this.datePublished = datePublished;
        this.status = status;
        this.versionType = versionType;
    }

    /** The raw version string as the index returned it, for comparison and display cleaning. */
    public String versionNumber() {
        return versionNumber;
    }

    public SemVer version() {
        return version;
    }

    public List<String> loaders() {
        return loaders;
    }

    public List<String> gameVersions() {
        return gameVersions;
    }

    public Instant datePublished() {
        return datePublished;
    }

    public String status() {
        return status;
    }

    public String versionType() {
        return versionType;
    }
}
