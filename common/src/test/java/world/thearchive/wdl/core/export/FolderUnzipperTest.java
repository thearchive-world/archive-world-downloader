// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderUnzipperTest {
    @Test
    void roundTripIsFileContentIdentical(@TempDir Path work) throws IOException {
        Path source = work.resolve("World");
        write(source.resolve("level.dat"), 1);
        write(source.resolve("region/r.0.0.mca"), 2);
        write(source.resolve("wdl/download.jsonl"), 3);
        write(source.resolve("session.lock"), 4); // zipper skips it
        Path zip = work.resolve("World.zip");
        FolderZipper.zip(source, zip);
        Path out = Files.createDirectories(work.resolve("out"));
        FolderUnzipper.extract(zip, "World", out);
        Path extracted = out.resolve("World");
        assertArrayEquals(Files.readAllBytes(source.resolve("level.dat")),
                Files.readAllBytes(extracted.resolve("level.dat")));
        assertArrayEquals(Files.readAllBytes(source.resolve("region/r.0.0.mca")),
                Files.readAllBytes(extracted.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(extracted.resolve("session.lock")));
    }

    @Test
    void refusesSlipRootMismatchMembersDuplicatesAndMissingAssertions(@TempDir Path work) throws IOException {
        Path out = Files.createDirectories(work.resolve("out"));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "a.zip", "World/../../evil", "World/level.dat", "World/wdl/download.jsonl"),
                "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "b.zip", "Other/level.dat", "Other/wdl/download.jsonl"), "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "c.zip", "World/level.dat", "World/wdl/download.jsonl",
                        "World/playerdata/u.dat"),
                "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "d.zip", "World/level.dat", "World/wdl/download.jsonl",
                        "World/Thing", "World/thing"),
                "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "h.zip", "World/level.dat", "World/wdl/download.jsonl",
                        "World/x", "World/x/y"),
                "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "e.zip", "World/level.dat"), "World", out));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "f.zip", "World/wdl/download.jsonl"), "World", out));
    }

    @Test
    void skipsSentinelAndLockNeverMaterializesDirectoryEntries(@TempDir Path work) throws IOException {
        Path out = Files.createDirectories(work.resolve("out"));
        FolderUnzipper.extract(craft(work, "g.zip", "World/", "World/level.dat",
                "World/wdl/download.jsonl", "World/wdl/download.pending", "World/session.lock",
                "World/empty-dir/"), "World", out);
        Path extracted = out.resolve("World");
        assertTrue(Files.isRegularFile(extracted.resolve("level.dat")));
        // A backup-sourced restore must read as its last recorded health, never as a RECOVERABLE crash.
        assertFalse(Files.exists(extracted.resolve("wdl/download.pending")));
        assertFalse(Files.exists(extracted.resolve("session.lock")));
        assertFalse(Files.exists(extracted.resolve("empty-dir")));
    }

    @Test
    void refusesAnEntryOverThePerEntrySizeCapAndLeavesTheTargetParentEmpty(@TempDir Path work)
            throws IOException {
        // The two-byte level.dat entry crosses a cap of one and aborts before a byte lands.
        Path zip = craft(work, "bomb.zip", "World/level.dat", "World/wdl/download.jsonl");
        Path out = Files.createDirectories(work.resolve("out"));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(zip, "World", out, 1L));
        try (Stream<Path> children = Files.list(out)) {
            assertEquals(0, children.count(), "no partial tree lands under the target parent");
        }
        FolderUnzipper.extract(zip, "World", out);
        assertTrue(Files.isRegularFile(out.resolve("World/level.dat")));
    }

    @Test
    void refusesBackslashHiddenTraversalEntry(@TempDir Path work) throws IOException {
        Path out = Files.createDirectories(work.resolve("out"));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(
                craft(work, "back.zip", "World/level.dat", "World/wdl/download.jsonl", "World/..\\evil"),
                "World", out));
        try (Stream<Path> children = Files.list(out)) {
            assertEquals(0, children.count(), "no partial tree lands under the target parent");
        }
    }

    @Test
    void corruptZipRefusesAndLeavesTheTargetParentEmpty(@TempDir Path work) throws IOException {
        Path zip = Files.write(work.resolve("corrupt.zip"), new byte[] { 1, 2, 3, 4 });
        Path out = Files.createDirectories(work.resolve("out"));
        assertThrows(IOException.class, () -> FolderUnzipper.extract(zip, "World", out));
        try (Stream<Path> children = Files.list(out)) {
            assertEquals(0, children.count(), "no partial tree lands under the target parent");
        }
    }

    private static Path craft(Path work, String name, String... entryPaths) throws IOException {
        Path target = work.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String entryPath : entryPaths) {
                out.putNextEntry(new ZipEntry(entryPath));
                out.write(entryPath.endsWith("/") ? new byte[0] : new byte[] { 7, 7 });
                out.closeEntry();
            }
        }
        return target;
    }

    private static void write(Path file, int contentByte) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] { (byte) contentByte });
    }
}
