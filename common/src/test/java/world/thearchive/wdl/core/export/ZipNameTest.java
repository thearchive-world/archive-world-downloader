// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MC-free next-free naming and collision model for the export and resume-backup zips. */
class ZipNameTest {
    @Test
    void exportWithoutCollisionTakesTheBareName(@TempDir Path saves) {
        assertEquals(saves.resolve("world.zip"), ZipName.nextFreeExport(saves, "world"));
    }

    @Test
    void exportCollisionCountsFromTwoLeavingExistingFilesUntouched(@TempDir Path saves) throws IOException {
        Files.write(saves.resolve("world.zip"), new byte[] { 1 });

        assertEquals(saves.resolve("world_(2).zip"), ZipName.nextFreeExport(saves, "world"),
                "the first collision takes _(2), the un-suffixed export staying the oldest");

        Files.write(saves.resolve("world_(2).zip"), new byte[] { 2 });
        assertEquals(saves.resolve("world_(3).zip"), ZipName.nextFreeExport(saves, "world"),
                "with _(2) also present the next free slot is _(3)");

        assertTrue(Files.exists(saves.resolve("world.zip")), "resolving a name never renames or removes an existing");
        assertTrue(Files.exists(saves.resolve("world_(2).zip")), "existing files are left untouched");
    }

    @Test
    void backupCarriesTheBackupSuffixBeforeTheCounter(@TempDir Path saves) throws IOException {
        assertEquals(saves.resolve("world-pre-resume.zip"), ZipName.nextFreeBackup(saves, "world"),
                "only the resume backup carries the -pre-resume suffix");

        Files.write(saves.resolve("world-pre-resume.zip"), new byte[] { 1 });
        assertEquals(saves.resolve("world-pre-resume_(2).zip"), ZipName.nextFreeBackup(saves, "world"),
                "a second same-day resume counts from _(2) after the -pre-resume suffix");
    }

    @Test
    void theCounterSitsBeforeTheZipExtensionNeverAfter(@TempDir Path saves) throws IOException {
        Files.write(saves.resolve("world.zip"), new byte[] { 1 });

        String name = ZipName.nextFreeExport(saves, "world").getFileName().toString();

        assertTrue(name.endsWith(".zip"), "the counter is inserted before the extension, so .zip stays last");
        assertEquals("world_(2).zip", name);
        assertFalse(name.contains(".zip_("), "the counter never lands after the extension");
    }

    @Test
    void theDerivedNameStaysInsideTheSavesDirectory(@TempDir Path saves) {
        Path export = ZipName.nextFreeExport(saves, "world");
        Path backup = ZipName.nextFreeBackup(saves, "world");

        assertEquals(saves, export.getParent(), "the export lands directly beside the folder in saves/");
        assertEquals(saves, backup.getParent(), "the backup lands directly beside the folder in saves/");
        assertTrue(export.startsWith(saves), "the derived export name is contained by the saves directory");
        assertTrue(backup.startsWith(saves), "the derived backup name is contained by the saves directory");
    }

    @Test
    void singlePlayerStemCountsFromTwoAndNeverOverwrites(@TempDir Path directory) throws IOException {
        assertEquals("World-singleplayer.zip",
                ZipName.nextFreeSinglePlayer(directory, "World").getFileName().toString());
        Files.createFile(directory.resolve("World-singleplayer.zip"));
        assertEquals("World-singleplayer_(2).zip",
                ZipName.nextFreeSinglePlayer(directory, "World").getFileName().toString());
    }
}
