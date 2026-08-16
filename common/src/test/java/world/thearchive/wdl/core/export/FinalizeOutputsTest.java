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
import java.util.stream.Stream;
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
    void exportOnDotSuffixedRootLandsBesideTheFolderNotInsideIt(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        // The shape the level directory takes below 1.20.5, where it is read through LevelResource.ROOT, whose id
        // is a bare dot, so the path arrives ending in a dot component.
        Path dotSuffixed = folder.resolve(".");

        String written = FinalizeOutputs.exportZip(dotSuffixed, true, new SaveProgress());

        assertEquals("world.zip", written, "the archive is named for the folder, not the dot component");
        assertTrue(Files.exists(saves.resolve("world.zip")),
                "the export lands in the saves directory, beside the folder");
        try (Stream<Path> inside = Files.list(folder)) {
            assertFalse(inside.anyMatch(path -> path.getFileName().toString().endsWith(".zip")),
                    "no archive is ever created inside the folder being zipped");
        }
    }

    @Test
    void compressingReachesFullWithStaleStagingFileInTheFolder(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        // A leftover staging file from an earlier run; the zip skips it, so the size denominator must skip it too or
        // the compressing bar never reaches full.
        Files.write(folder.resolve("wdl-export-stale.part"), new byte[10_000]);
        SaveProgress progress = new SaveProgress();

        FinalizeOutputs.exportZip(folder, true, progress);

        assertEquals(1.0f, progress.fraction(), 0.0f,
                "the compressing bar reaches full: the size denominator excludes the staging file the zip also skips");
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
    void theAdvertisedBackupNameIsTheOneTheNextResumeWrites(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.RESUME, true);

        String advertised = FinalizeOutputs.nextBackupName(saves, "world");
        FinalizeOutputs.backupBeforeResume(folder, DownloadMode.RESUME, true);

        assertEquals("world-pre-resume_(2).zip", advertised,
                "the bare stem holds the first resume's backup, so the second one is disambiguated");
        assertTrue(Files.exists(saves.resolve(advertised)),
                "the name shown before the resume is the file the resume actually wrote");
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
    void resumeBackupOnDotSuffixedRootLandsBesideTheFolder(@TempDir Path saves) throws IOException {
        Path folder = saveFolder(saves);
        Path dotSuffixed = folder.resolve(".");

        FinalizeOutputs.backupBeforeResume(dotSuffixed, DownloadMode.RESUME, true);

        assertTrue(Files.exists(saves.resolve("world-pre-resume.zip")),
                "the resume backup lands beside the folder on a dot-suffixed root too");
        try (Stream<Path> inside = Files.list(folder)) {
            assertFalse(inside.anyMatch(path -> path.getFileName().toString().endsWith(".zip")),
                    "no backup is created inside the folder being zipped");
        }
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
