// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** The one exclusion set both the export zip and the size total consult, so the two never drift apart. */
class SaveWalkTest {
    private static final Path ROOT = Paths.get("saves", "world");

    @Test
    void excludesTheSessionLockThePendingSentinelAndStagingParts() {
        assertTrue(SaveWalk.isExcluded(ROOT, ROOT.resolve("session.lock")));
        assertTrue(SaveWalk.isExcluded(ROOT, ROOT.resolve("wdl-export-abc123.part")));
        assertTrue(SaveWalk.isExcluded(ROOT, ROOT.resolve("wdl").resolve("download.pending")));
    }

    @Test
    void keepsWorldDataAndNamesThatOnlyHalfMatchAnExclusion() {
        assertFalse(SaveWalk.isExcluded(ROOT, ROOT.resolve("level.dat")));
        assertFalse(SaveWalk.isExcluded(ROOT, ROOT.resolve("region").resolve("r.0.0.mca")));
        // A prefix-only or suffix-only name is real data, not the zipper's staging file, so it stays in the walk.
        assertFalse(SaveWalk.isExcluded(ROOT, ROOT.resolve("wdl-export-notes.txt")));
        assertFalse(SaveWalk.isExcluded(ROOT, ROOT.resolve("data.part")));
        // The sentinel is skipped only at its exact wdl subpath, never a bare top-level download.pending.
        assertFalse(SaveWalk.isExcluded(ROOT, ROOT.resolve("download.pending")));
    }
}
