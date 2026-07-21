// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The strict finished-timestamp read for restore-source discovery: the newest parseable finishedAt across a download
 * report log, or empty. Deliberately stricter than the report screen's lenient parser, which EPOCH-defaults a bad
 * timestamp; discovery must EXCLUDE such a candidate, never order it at the epoch.
 */
public final class StrictReportReader {
    private static final String KEY = "\"finishedAt\":\"";

    private StrictReportReader() {}

    /** Newest parseable finishedAt across all lines; empty when none parses. IO errors propagate. */
    public static Optional<Instant> latestFinishedAt(BufferedReader reader) throws IOException {
        Instant latest = null;
        String line;
        while ((line = reader.readLine()) != null) {
            int start = line.indexOf(KEY);
            if (start < 0) {
                continue;
            }
            int valueStart = start + KEY.length();
            int valueEnd = line.indexOf('"', valueStart);
            if (valueEnd < 0) {
                continue;
            }
            try {
                Instant parsed = Instant.parse(line.substring(valueStart, valueEnd));
                if (latest == null || parsed.isAfter(latest)) {
                    latest = parsed;
                }
            } catch (DateTimeParseException e) {
                // Strict: an unparseable timestamp excludes this line, never epoch-defaults.
            }
        }
        return Optional.ofNullable(latest);
    }
}
