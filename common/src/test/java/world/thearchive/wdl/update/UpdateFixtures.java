// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.update.SemVer;

/** Shared fixtures for the update package tests: runtime info, a well-formed body, and a recording transport. */
final class UpdateFixtures {
    static final RuntimeInfo INFO = new RuntimeInfo("fabric", "1.21.11",
            SemVer.parse("1.0.0-SNAPSHOT").orElseThrow(), "1.0.0-SNAPSHOT+1.21.11", "x6JOJ1sf");

    static final String WELL_FORMED_BODY = """
            [{
              "version_number": "3.9.5+26.1-fabric",
              "version_type": "release",
              "status": "listed",
              "date_published": "2026-06-01T00:00:00Z",
              "loaders": ["fabric", "neoforge"],
              "game_versions": ["1.21.11"]
            }]""";

    private UpdateFixtures() {}

    static final class RecordingTransport implements Transport {
        private final Result result;
        int calls;
        @Nullable
        URI uri;
        @Nullable
        Map<String, String> headers;

        RecordingTransport(Result result) {
            this.result = result;
        }

        @Override
        public Result fetch(URI uri, Map<String, String> headers) {
            calls++;
            this.uri = uri;
            this.headers = headers;
            return result;
        }
    }
}
