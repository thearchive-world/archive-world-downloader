// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.time.Instant;

public final class DownloadIdentity {
    private final String id;
    private final Instant startedAt;
    private final String downloaderName;
    private final String downloaderUuid;
    private final String sourceAddress;
    private final String sourceName;
    private final String sourceMotd;
    private final String loaderName;
    private final String loaderVersion;
    private final String downloadName;
    private final String sourceKind;

    public DownloadIdentity(String id, Instant startedAt, String downloaderName, String downloaderUuid,
            String sourceAddress, String sourceName, String sourceMotd, String loaderName,
            String loaderVersion, String downloadName, String sourceKind) {
        this.id = id;
        this.startedAt = startedAt;
        this.downloaderName = downloaderName;
        this.downloaderUuid = downloaderUuid;
        this.sourceAddress = sourceAddress;
        this.sourceName = sourceName;
        this.sourceMotd = sourceMotd;
        this.loaderName = loaderName;
        this.loaderVersion = loaderVersion;
        this.downloadName = downloadName;
        this.sourceKind = sourceKind;
    }

    public String id() {
        return id;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String downloaderName() {
        return downloaderName;
    }

    public String downloaderUuid() {
        return downloaderUuid;
    }

    public String sourceAddress() {
        return sourceAddress;
    }

    public String sourceName() {
        return sourceName;
    }

    public String sourceMotd() {
        return sourceMotd;
    }

    public String loaderName() {
        return loaderName;
    }

    public String loaderVersion() {
        return loaderVersion;
    }

    /** The download's user-facing name, or empty in a legacy record predating the field. */
    public String downloadName() {
        return downloadName;
    }

    public String sourceKind() {
        return sourceKind;
    }
}
