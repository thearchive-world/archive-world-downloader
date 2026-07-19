// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.SaveFolders.worldFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.core.DownloadMode;
import world.thearchive.wdl.core.SaveProgress;

/** The MC-free finalize-output orchestration: export zip and resume backup, mode/knob gating, collision. */
class FinalizeOutputsTest {
    /** A small finished save folder under a saves directory, returned as the folder path. */
    private static Path saveFolder(Path saves) throws IOException {
        return worldFolder(saves, new byte[200], new byte[300]);
    }

    @Test
    void exportOnWritesTheZipBesideTheFolder(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        FinalizeOutputs.exportZip(folder, true, new SaveProgress());

        assertTrue(Files.exists(saves.resolve("world.zip")), "the export zip lands beside the folder");
        assertTrue(Files.exists(folder.resolve("level.dat")), "the openable folder is untouched");
    }

    @Test
    void noZipWhenExportIsOff(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        FinalizeOutputs.exportZip(folder, false, new SaveProgress());

        assertFalse(Files.exists(saves.resolve("world.zip")), "no export zip when zipOnFinish is off");
    }

    @Test
    void exportReportsTheWrittenZipFileName(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        assertEquals("world.zip", FinalizeOutputs.exportZip(folder, true, new SaveProgress()),
                "the completion toast surfaces the name of the zip actually written");
    }

    @Test
    void exportReportsTheCollisionSuffixedZipFileName(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        Files.write(saves.resolve("world.zip"), new byte[] { 42 }); // a prior export

        assertEquals("world_(2).zip", FinalizeOutputs.exportZip(folder, true, new SaveProgress()),
                "the reported name is the disambiguated one, not the bare stem");
    }

    @Test
    void exportOffReportsNoZipFileName(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        assertNull(FinalizeOutputs.exportZip(folder, false, new SaveProgress()), "no zip written, no name to surface");
    }

    @Test
    void exportNeverOverwritesAnExistingZip(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        Files.write(saves.resolve("world.zip"), new byte[] { 42 }); // a prior export

        FinalizeOutputs.exportZip(folder, true, new SaveProgress());

        assertEquals(1, Files.size(saves.resolve("world.zip")), "the prior export is left untouched");
        assertTrue(Files.exists(saves.resolve("world_(2).zip")), "the new export takes the next free slot");
    }

    @Test
    void resumeBacksUpOnlyOnResumeWithTheKnobOn(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.RESUME, true);
        assertTrue(Files.exists(saves.resolve("world-pre-resume.zip")), "a resume backs up before the merge");

        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.RESUME, true);
        assertTrue(Files.exists(saves.resolve("world-pre-resume_(2).zip")), "a second resume counts from _(2)");
    }

    @Test
    void noBackupForFreshDownload(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.NEW, true);

        assertFalse(Files.exists(saves.resolve("world-pre-resume.zip")), "a fresh download has no folder to protect");
    }

    @Test
    void noBackupWhenTheResumeKnobIsOff(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);

        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.RESUME, false);

        assertFalse(Files.exists(saves.resolve("world-pre-resume.zip")), "zipOnResume off skips the backup");
    }

    @Test
    void theSessionLockIsNeverExported(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves); // level.dat(200) + region/r.0.0.mca(300)
        Files.write(folder.resolve("session.lock"), new byte[3]); // the transient, resume-time OS-locked marker

        FinalizeOutputs.exportZip(folder, true, new SaveProgress());

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(saves.resolve("world.zip").toFile())) {
            assertNull(zip.getEntry("world/session.lock"), "the export never ships the transient session lock");
        }
    }

    @Test
    void aFailedExportLeavesTheOpenableFolderIntact(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        // Force the export to fail by making the saves directory unwritable, so the zip's staging file cannot be
        // created, while the folder itself stays readable and untouched.
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(saves);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions are unavailable on this filesystem");
            return;
        }
        Files.setPosixFilePermissions(saves,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            Assumptions.assumeTrue(!isWritable(saves),
                    "the saves directory is still writable (running as root?); cannot force the export to fail");

            assertNull(FinalizeOutputs.exportZip(folder, true, new SaveProgress()),
                    "a failed export reports no zip name, so no surface can claim a zip that does not exist");

            assertFalse(Files.exists(saves.resolve("world.zip")), "the failed export left no artifact");
            assertTrue(Files.exists(folder.resolve("level.dat")), "the openable folder stays intact");
        } finally {
            Files.setPosixFilePermissions(saves, original); // restore so the temporary-directory cleanup can remove it
        }
    }

    private static boolean isWritable(Path directory) {
        try {
            Path probe = Files.createTempFile(directory, "probe", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
