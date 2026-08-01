// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The strictly-newer decision: client-side re-filter, newest by date with a semver tie-break. */
class LatestReleaseTest {
    private static final SemVer RUNNING = SemVer.parse("1.0.0").orElseThrow();

    private static Release release(String versionNumber, List<String> loaders, String gameVersion, String published,
            String status, String versionType) {
        return new Release(versionNumber, SemVer.parse(versionNumber).orElseThrow(), loaders, List.of(gameVersion),
                Instant.parse(published), status, versionType);
    }

    private static Release listed(String versionNumber, String published) {
        return release(versionNumber, List.of("fabric", "neoforge"), "1.21.11", published, "listed", "release");
    }

    @Test
    void returnsTheStrictlyNewerListedRelease() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(listed("3.9.5+26.1-fabric", "2026-06-01T00:00:00Z")));

        assertEquals(Optional.of("3.9.5+26.1-fabric"), newer, "the raw version_number comes back, not a cleaned form");
    }

    @Test
    void aBareReleaseIsNewerThanItsRunningPrerelease() {
        SemVer snapshot = SemVer.parse("1.0.0-SNAPSHOT").orElseThrow();

        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", snapshot,
                List.of(listed("1.0.0", "2026-06-01T00:00:00Z")));

        assertEquals(Optional.of("1.0.0"), newer);
    }

    @Test
    void dropsTheWrongLoaderRow() {
        Optional<String> newer = LatestRelease.newerThan("neoforge", "1.21.11", RUNNING,
                List.of(release("3.9.5", List.of("fabric"), "1.21.11", "2026-06-01T00:00:00Z", "listed", "release")));

        assertFalse(newer.isPresent());
    }

    @Test
    void dropsTheWrongMcVersionRow() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(release("3.9.5", List.of("fabric"), "1.21.4", "2026-06-01T00:00:00Z", "listed", "release")));

        assertFalse(newer.isPresent());
    }

    @Test
    void dropsTheNonListedRow() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(release("3.9.5", List.of("fabric"), "1.21.11", "2026-06-01T00:00:00Z", "draft", "release")));

        assertFalse(newer.isPresent());
    }

    @Test
    void dropsTheNonReleaseRow() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(release("3.9.5", List.of("fabric"), "1.21.11", "2026-06-01T00:00:00Z", "listed", "beta")));

        assertFalse(newer.isPresent());
    }

    @Test
    void picksTheNewestByDatePublished() {
        // The chosen algorithm is date-first: a higher semver published earlier
        // loses to a later backport, and the strictly-newer guard keeps that residual to under-notification.
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(listed("3.9.5", "2026-01-01T00:00:00Z"), listed("3.9.4", "2026-02-01T00:00:00Z")));

        assertEquals(Optional.of("3.9.4"), newer);
    }

    @Test
    void breaksDateTiesBySemver() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(listed("3.9.4", "2026-06-01T00:00:00Z"), listed("3.9.5", "2026-06-01T00:00:00Z")));

        assertEquals(Optional.of("3.9.5"), newer);
    }

    @Test
    void anEqualVersionYieldsEmpty() {
        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", RUNNING,
                List.of(listed("1.0.0", "2026-06-01T00:00:00Z")));

        assertFalse(newer.isPresent(), "only a strictly newer version is reported, never the same");
    }

    @Test
    void anOlderVersionYieldsEmpty() {
        SemVer running = SemVer.parse("4.0.0").orElseThrow();

        Optional<String> newer = LatestRelease.newerThan("fabric", "1.21.11", running,
                List.of(listed("3.9.5", "2026-06-01T00:00:00Z")));

        assertFalse(newer.isPresent(), "only a strictly newer version is reported, never a downgrade");
    }

    @Test
    void emptyRowsYieldEmpty() {
        assertFalse(LatestRelease.newerThan("fabric", "1.21.11", RUNNING, List.of()).isPresent());
    }
}
