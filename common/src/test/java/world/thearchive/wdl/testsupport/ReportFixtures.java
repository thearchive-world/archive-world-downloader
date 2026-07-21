// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import world.thearchive.wdl.core.report.DownloadIdentity;
import world.thearchive.wdl.core.report.ReportEnvironment;

/**
 * Shared download-report fixtures for the report and browse tests: a DownloadIdentity and a ReportEnvironment over
 * fixed downloader, server, and loader constants, plus the debug.log_saved_chunks settings map, so the four report and
 * browse tests share one construction rather than each carrying its own.
 */
public final class ReportFixtures {
    private static final Instant STARTED = Instant.parse("2026-06-22T07:14:01Z");

    private ReportFixtures() {}

    /** A DownloadIdentity over the fixed downloader/server/loader constants, varying source and name fields. */
    public static DownloadIdentity identity(String id, String sourceAddress, String sourceMotd,
            String downloadName) {
        return new DownloadIdentity(id, STARTED, "Terbin", "uuid", sourceAddress, "Survival", sourceMotd,
                "NeoForge", "21.11.42", downloadName, "");
    }

    /** A ReportEnvironment for a Paper overworld on 1.21.11 carrying the given {@code modVersion}. */
    public static ReportEnvironment environment(String modVersion) {
        return new ReportEnvironment("Paper", 8, "overworld", "1.21.11", modVersion);
    }

    /** A settings map with the single {@code debug.log_saved_chunks} entry set to true. */
    public static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("debug.log_saved_chunks", "true");
        return settings;
    }
}
