// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StrictReportReaderTest {
    private static Optional<Instant> read(String jsonl) throws IOException {
        return StrictReportReader.latestFinishedAt(new BufferedReader(new StringReader(jsonl)));
    }

    @Test
    void newestParseableFinishWins() throws IOException {
        Optional<Instant> latest = read(""
                + "{\"id\":\"a\",\"finishedAt\":\"2026-01-05T10:00:00Z\"}\n"
                + "{\"id\":\"b\",\"finishedAt\":\"2026-03-01T10:00:00Z\"}\n"
                + "{\"id\":\"c\",\"finishedAt\":\"2026-02-01T10:00:00Z\"}\n");
        assertTrue(latest.isPresent());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), latest.get());
    }

    @Test
    void unparseableTimestampIsSkippedNotEpochDefaulted() throws IOException {
        // The lenient shipped parser would EPOCH-default this; strict skips the line.
        Optional<Instant> latest = read("{\"finishedAt\":\"not-a-time\"}\n");
        assertFalse(latest.isPresent());
    }

    @Test
    void absentKeyMalformedLineAndEmptyInputAreEmpty() throws IOException {
        assertFalse(read("{\"id\":\"a\"}\n").isPresent());
        assertFalse(read("{ this is not json\n").isPresent());
        assertFalse(read("").isPresent());
    }

    @Test
    void malformedLinesDoNotHideLaterGoodLines() throws IOException {
        Optional<Instant> latest = read("garbage\n{\"finishedAt\":\"2026-03-01T10:00:00Z\"}\n");
        assertEquals(Optional.of(Instant.parse("2026-03-01T10:00:00Z")), latest);
    }
}
