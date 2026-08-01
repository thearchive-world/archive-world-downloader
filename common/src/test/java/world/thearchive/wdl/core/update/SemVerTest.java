// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The version parse and precedence rules of the update check: tolerant in, total, never a throw. */
class SemVerTest {
    private static SemVer version(String raw) {
        return SemVer.parse(raw).orElseThrow(() -> new AssertionError("expected " + raw + " to parse"));
    }

    @Test
    void equalVersionsCompareEqual() {
        assertEquals(0, version("1.2.3").compareTo(version("1.2.3")));
    }

    @Test
    void toleratesTheLeadingV() {
        assertEquals(0, version("v1.2.3").compareTo(version("1.2.3")));
    }

    @Test
    void ordersByNumericComponentsNotLexically() {
        assertTrue(version("1.2.10").compareTo(version("1.2.9")) > 0, "10 > 9 numerically, though not as strings");
        assertTrue(version("1.10.0").compareTo(version("1.9.9")) > 0);
        assertTrue(version("2.0.0").compareTo(version("1.99.99")) > 0);
        assertTrue(version("1.2.9").compareTo(version("1.2.10")) < 0);
    }

    @Test
    void discardsBuildMetadataBeforeThePrereleaseSplit() {
        // The live shape 3.9.5+26.1-fabric hides a hyphen inside the build tail: splitting on '-' first
        // would mis-read fabric as a prerelease and fail the numeric parse of 3.9.5+26.1.
        assertEquals(0, version("3.9.5+26.1-fabric").compareTo(version("3.9.5")));
    }

    @Test
    void buildMetadataNeverAffectsOrdering() {
        assertEquals(0, version("1.2.3+build7").compareTo(version("1.2.3+build8")));
    }

    @Test
    void prereleaseSortsBelowItsBareRelease() {
        assertTrue(version("1.0.0-SNAPSHOT").compareTo(version("1.0.0")) < 0);
        assertTrue(version("1.0.0").compareTo(version("1.0.0-SNAPSHOT")) > 0);
    }

    @Test
    void refusesMalformedStringsWithoutThrowing() {
        assertFalse(SemVer.parse("").isPresent());
        assertFalse(SemVer.parse("banana").isPresent());
        assertFalse(SemVer.parse("1.2").isPresent());
        assertFalse(SemVer.parse("1.2.3.4").isPresent());
        assertFalse(SemVer.parse("1.2.x").isPresent());
        assertFalse(SemVer.parse("1.2.3-").isPresent());
        assertFalse(SemVer.parse("1.-2.3").isPresent());
    }

    @Test
    void displayStripsTheBuildTailAndLeadingV() {
        assertEquals("3.9.5", SemVer.display("v3.9.5+26.1-fabric"));
    }

    @Test
    void displayKeepsPrereleasesVerbatim() {
        assertEquals("1.2.0-rc1", SemVer.display("1.2.0-rc1"));
    }

    @Test
    void displayIsTotalOnNonVersionStrings() {
        assertEquals("unknown", SemVer.display("unknown"));
    }
}
