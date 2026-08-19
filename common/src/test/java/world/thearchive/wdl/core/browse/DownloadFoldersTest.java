// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.DownloadTarget;
import world.thearchive.wdl.core.report.DownloadReportStore;

class DownloadFoldersTest {
    @Test
    void isWdlManagedRecognizesFolderWithReportRecord(@TempDir Path saves) throws IOException {
        markManaged(saves.resolve("download-2026-06-22"));
        assertTrue(DownloadFolders.isWdlManaged(saves.resolve("download-2026-06-22")));
    }

    @Test
    void isWdlManagedRecognizesRecoverableFolderByCrashSentinel(@TempDir Path saves) throws IOException {
        markRecoverable(saves.resolve("crashed-2026-06-22"));
        assertTrue(DownloadFolders.isWdlManaged(saves.resolve("crashed-2026-06-22")));
    }

    @Test
    void isWdlManagedRejectsPlainVanillaSave(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("vanilla-save"));
        assertFalse(DownloadFolders.isWdlManaged(saves.resolve("vanilla-save")));
    }

    @Test
    void isWdlManagedRejectsAbsentFolder(@TempDir Path saves) {
        assertFalse(DownloadFolders.isWdlManaged(saves.resolve("nope")));
    }

    @Test
    void resolveManagedResumeReturnsResumeTargetForManagedFolder(@TempDir Path saves) throws IOException {
        markManaged(saves.resolve("mybase-2026-06-22"));
        DownloadTarget target = DownloadFolders.resolveManagedResume("mybase-2026-06-22", saves);
        assertNotNull(target);
        assertEquals("mybase-2026-06-22", target.folderName());
        assertEquals(DownloadMode.RESUME, target.mode());
    }

    @Test
    void resolveManagedResumeRejectsNameThatDoesNotExist(@TempDir Path saves) {
        assertNull(DownloadFolders.resolveManagedResume("does-not-exist", saves));
    }

    @Test
    void resolveManagedResumeRejectsPlainVanillaSave(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("vanilla-save"));
        assertNull(DownloadFolders.resolveManagedResume("vanilla-save", saves));
    }

    @Test
    void resolveManagedResumeRejectsNameThatSanitizesToEmpty(@TempDir Path saves) throws IOException {
        // The saves directory is itself marked managed, which is why an empty name has somewhere to resolve to:
        // without it the probe rejects the empty name by accident and the empty-name guard is unobservable.
        markManaged(saves);
        assertNull(DownloadFolders.resolveManagedResume("..", saves),
                "an empty-after-sanitize name is a rejection, not the default-name fallback");
    }

    @Test
    void resolveManagedResumeContainsAndRejectsTraversingName(@TempDir Path root) throws IOException {
        // The traversal target is a real managed folder beside the saves directory, so an unsanitized name
        // would find it: a target outside saves that no later guard can recognize, because resolveResume
        // reads the traversed path back to its bare basename.
        Path saves = Files.createDirectories(root.resolve("saves"));
        markManaged(root.resolve("outside-saves"));
        assertNull(DownloadFolders.resolveManagedResume("../outside-saves", saves),
                "a traversing name is sanitized to a contained component, which is not a managed folder");
    }

    @Test
    void resolveManagedResumeResolvesSpacedManagedFolder(@TempDir Path saves) throws IOException {
        markManaged(saves.resolve("My Base-2026-06-22"));
        DownloadTarget target = DownloadFolders.resolveManagedResume("My Base-2026-06-22", saves);
        assertNotNull(target);
        assertEquals("My Base-2026-06-22", target.folderName());
    }

    @Test
    void listManagedReturnsOnlyWdlManagedFolderNamesSorted(@TempDir Path saves) throws IOException {
        markManaged(saves.resolve("complete-2026-06-22"));
        markRecoverable(saves.resolve("crashed-2026-06-22"));
        Files.createDirectories(saves.resolve("vanilla-save"));
        assertEquals(ImmutableList.of("complete-2026-06-22", "crashed-2026-06-22"), DownloadFolders.listManaged(saves),
                "a recoverable folder is included, a plain vanilla save is excluded");
    }

    @Test
    void listManagedReturnsEmptyForAnAbsentSavesDirectory(@TempDir Path saves) throws IOException {
        assertTrue(DownloadFolders.listManaged(saves.resolve("no-such-dir")).isEmpty());
    }

    private static void markManaged(Path folder) throws IOException {
        Path machine = DownloadReportStore.machineFile(folder);
        Files.createDirectories(machine.getParent());
        Files.write(machine, new byte[0]);
    }

    private static void markRecoverable(Path folder) throws IOException {
        Path pending = DownloadReportStore.pendingFile(folder);
        Files.createDirectories(pending.getParent());
        Files.write(pending, new byte[0]);
    }
}
