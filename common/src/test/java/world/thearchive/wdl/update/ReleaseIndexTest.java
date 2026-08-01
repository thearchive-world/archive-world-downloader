// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.update.Release;
import world.thearchive.wdl.core.update.SemVer;

/**
 * The index request contract and the fail-soft parse: every failure shape yields an empty list and no thrown error,
 * exercised through an injected fake transport, never a socket.
 */
class ReleaseIndexTest {
    @Test
    void requestCarriesTheIndexContract() {
        UpdateFixtures.RecordingTransport transport = new UpdateFixtures.RecordingTransport(
                new Transport.Result(200, "[]"));

        ReleaseIndex.fetch(UpdateFixtures.INFO, transport);

        URI uri = transport.uri;
        assertNotNull(uri);
        assertEquals("api.modrinth.com", uri.getHost());
        assertEquals("/v2/project/x6JOJ1sf/version", uri.getPath());
        String query = uri.getRawQuery();
        assertTrue(query.contains("loaders=%5B%22fabric%22%5D"),
                "loaders is a URL-encoded JSON array, the server-side pre-filter: " + query);
        assertTrue(query.contains("game_versions=%5B%221.21.11%22%5D"), query);
        assertTrue(query.contains("include_changelog=false"), query);
        Map<String, String> headers = transport.headers;
        assertNotNull(headers);
        String userAgent = headers.get("User-Agent");
        assertNotNull(userAgent, "the index requires a descriptive User-Agent");
        assertTrue(userAgent.contains("archive-wdl"), userAgent);
        assertTrue(userAgent.contains("1.0.0-SNAPSHOT+1.21.11"), userAgent);
        assertTrue(userAgent.contains("https://"), "the User-Agent carries a contact URL: " + userAgent);
    }

    @Test
    void aWellFormedBodyYieldsItsRows() {
        List<Release> rows = ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, UpdateFixtures.WELL_FORMED_BODY)));

        assertEquals(1, rows.size());
        Release row = rows.get(0);
        assertEquals("3.9.5+26.1-fabric", row.versionNumber());
        assertEquals(0, row.version().compareTo(SemVer.parse("3.9.5").orElseThrow()));
        assertEquals(List.of("fabric", "neoforge"), row.loaders());
        assertEquals(List.of("1.21.11"), row.gameVersions());
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), row.datePublished());
        assertEquals("listed", row.status());
        assertEquals("release", row.versionType());
    }

    @Test
    void dropsRowsMissingRequiredFields() {
        String body = """
                [{
                  "version_type": "release",
                  "status": "listed",
                  "date_published": "2026-06-01T00:00:00Z",
                  "loaders": ["fabric"],
                  "game_versions": ["1.21.11"]
                }]""";

        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, body))).isEmpty(),
                "a row without a version_number cannot be compared and is dropped");
    }

    @Test
    void dropsRowsWithUnparseableVersionNumbers() {
        String body = UpdateFixtures.WELL_FORMED_BODY.replace("3.9.5+26.1-fabric", "banana");

        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, body))).isEmpty());
    }

    @Test
    void dropsRowsWithUnparseableDates() {
        String body = UpdateFixtures.WELL_FORMED_BODY.replace("2026-06-01T00:00:00Z", "yesterday");

        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, body))).isEmpty());
    }

    @Test
    void dropsOnlyTheMalformedRowNotItsSiblings() {
        String body = "[" + UpdateFixtures.WELL_FORMED_BODY.substring(1, UpdateFixtures.WELL_FORMED_BODY.length() - 1)
                + "," + "{\"version_number\": \"banana\"}]";

        assertEquals(1, ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, body))).size());
    }

    @Test
    void anHttpErrorStatusYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(500, "oops"))).isEmpty());
    }

    @Test
    void aRateLimitYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(429, "slow down"))).isEmpty());
    }

    @Test
    void aTransportFailureYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(Transport.Result.FAILURE)).isEmpty());
    }

    @Test
    void aNonJsonBodyYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, "<html>maintenance"))).isEmpty());
    }

    @Test
    void aNonArrayJsonBodyYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, "{\"error\":\"x\"}"))).isEmpty());
    }

    @Test
    void anEmptyArrayYieldsEmpty() {
        assertTrue(ReleaseIndex.fetch(UpdateFixtures.INFO,
                new UpdateFixtures.RecordingTransport(new Transport.Result(200, "[]"))).isEmpty());
    }
}
