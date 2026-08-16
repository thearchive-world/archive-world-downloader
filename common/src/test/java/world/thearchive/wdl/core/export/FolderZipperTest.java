// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.thearchive.wdl.testsupport.SaveFolders.worldFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MC-free java.util.zip folder zipper: the export/backup artifact, fail-soft over the openable folder. */
class FolderZipperTest {
    @Test
    void zipsEveryFileUnderTheFolderNameAndReturnsTheWalkedByteTotal(@TempDir Path saves) throws IOException {
        byte[] levelDat = new byte[] { 1, 2, 3, 4, 5 };
        Path folder = worldFolder(saves, levelDat, new byte[120]);
        Path target = saves.resolve("world.zip");

        long walked = FolderZipper.zip(folder, target);

        assertEquals(125, walked, "the returned total is the on-disk byte sum walked (the by-product)");
        assertTrue(Files.exists(target), "the export zip lands at the resolved name");
        assertTrue(Files.exists(folder.resolve("level.dat")), "the openable folder is only read, never touched");

        Map<String, byte[]> entries = readEntries(target);
        assertEquals(2, entries.size());
        assertArrayEquals(levelDat, entries.get("world/level.dat"),
                "entries are rooted at the folder name so unzipping yields saves/world/");
        assertNotNull(entries.get("world/region/r.0.0.mca"), "nested files keep their relative path");
    }

    @Test
    void reportsCumulativeZippedBytesPerFileForLiveProgress(@TempDir Path saves) throws IOException {
        Path folder = worldFolder(saves, new byte[5], new byte[120]);
        Path target = saves.resolve("world.zip");

        List<Long> reported = new ArrayList<>();
        long walked = FolderZipper.zip(folder, target, reported::add);

        assertEquals(125, walked);
        assertEquals(2, reported.size(), "the callback fires once per archived file");
        assertEquals(125L, reported.get(reported.size() - 1), "the last report is the full walked total");
        for (int i = 1; i < reported.size(); i++) {
            assertTrue(reported.get(i) > reported.get(i - 1), "cumulative bytes only ever increase");
        }
    }

    @Test
    void aFailedZipSurfacesTheErrorLeavesNoArtifactAndTheFolderIntact(@TempDir Path saves) throws IOException {
        Path folder = Files.createDirectories(saves.resolve("world"));
        Files.write(folder.resolve("level.dat"), new byte[] { 7 });
        // A non-empty directory standing where the zip would move into, so the final move fails and cleanup runs.
        Path target = Files.createDirectories(saves.resolve("occupied"));
        Files.write(target.resolve("keep.txt"), new byte[] { 9 });

        assertThrows(IOException.class, () -> FolderZipper.zip(folder, target),
                "a failed zip surfaces the error rather than swallowing it");

        assertTrue(Files.exists(folder.resolve("level.dat")), "the openable folder stays complete");
        assertTrue(Files.exists(target.resolve("keep.txt")), "the failed move never disturbed what was there");
        try (Stream<Path> siblings = Files.list(saves)) {
            assertFalse(siblings.anyMatch(path -> path.getFileName().toString().contains(".part")),
                    "no half-written staging file is left behind, and no valid-looking partial zip remains");
        }
    }

    @Test
    void excludesTheTransientSessionLockFromTheArchiveAndTheByteTotal(@TempDir Path saves) throws IOException {
        Path folder = Files.createDirectories(saves.resolve("world"));
        Files.write(folder.resolve("level.dat"), new byte[] { 1, 2, 3 });
        // MC's transient session-lock marker, held under an exclusive OS lock during a resume backup.
        Files.write(folder.resolve("session.lock"), new byte[] { 9, 9, 9 });
        Path target = saves.resolve("world.zip");

        long walked = FolderZipper.zip(folder, target);

        Map<String, byte[]> entries = readEntries(target);
        assertTrue(entries.containsKey("world/level.dat"), "world data is archived");
        assertFalse(entries.containsKey("world/session.lock"),
                "the session lock is never archived: it is transient, and on Windows the exclusive lock on it "
                        + "would fail the whole zip if read");
        assertEquals(3, walked, "its bytes are excluded from the walked total too");
    }

    @Test
    void excludesThePendingSentinelFromTheArchiveAndTheByteTotal(@TempDir Path saves) throws IOException {
        Path folder = Files.createDirectories(saves.resolve("world"));
        Files.write(folder.resolve("level.dat"), new byte[] { 1, 2, 3 });
        Path wdl = Files.createDirectories(folder.resolve("wdl"));
        Files.write(wdl.resolve("download.md"), new byte[] { 4, 4 });
        // The crash sentinel is written before the pre-resume backup by design (the crash marker must precede
        // any modification), so without this exclusion a hand-unzipped backup would carry a false crash signal.
        Files.write(wdl.resolve("download.pending"), new byte[] { 9 });
        Path target = saves.resolve("world.zip");

        long walked = FolderZipper.zip(folder, target);

        Map<String, byte[]> entries = readEntries(target);
        assertTrue(entries.containsKey("world/wdl/download.md"), "the prior session's rendering is archived");
        assertFalse(entries.containsKey("world/wdl/download.pending"),
                "the sentinel mirrors the restore extractor, which already strips it");
        assertEquals(5, walked, "its bytes are excluded from the walked total too");
    }

    @Test
    void excludesStaleExportPartFileFromTheArchiveAndTheByteTotal(@TempDir Path saves) throws IOException {
        Path folder = Files.createDirectories(saves.resolve("world"));
        Files.write(folder.resolve("level.dat"), new byte[] { 1, 2, 3 });
        // An export staging file left inside a save by an earlier run; it must never be archived back into a new zip.
        Files.write(folder.resolve("wdl-export-abc123.part"), new byte[500]);
        Path target = saves.resolve("world.zip");

        long walked = FolderZipper.zip(folder, target);

        Map<String, byte[]> entries = readEntries(target);
        assertTrue(entries.containsKey("world/level.dat"), "world data is archived");
        assertFalse(entries.containsKey("world/wdl-export-abc123.part"),
                "the export's own staging artifact is never archived, whether stale or in flight");
        assertEquals(3, walked, "its bytes are excluded from the walked total too");
    }

    private static Map<String, byte[]> readEntries(Path zip) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = zipFile.getInputStream(entry)) {
                    out.put(entry.getName(), readAll(in));
                }
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
