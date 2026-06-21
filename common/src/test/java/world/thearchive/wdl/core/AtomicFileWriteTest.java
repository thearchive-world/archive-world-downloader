// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The staged-write contract. */
class AtomicFileWriteTest {
    @Test
    void writesTheBytesAndCreatesTheMissingParent(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested").resolve("target.bin");

        AtomicFileWrite.write(file, new byte[] { 1, 2, 3 });

        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(file));
    }

    @Test
    void leavesNoStagedSiblingBehindOnSuccess(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("target.bin");

        AtomicFileWrite.write(file, new byte[] { 1 });
        AtomicFileWrite.write(file, new byte[] { 2 });

        assertArrayEquals(new byte[] { 2 }, Files.readAllBytes(file));
        assertFalse(Files.exists(directory.resolve("target.bin.tmp")),
                "the staged sibling is moved onto the file, never left beside it");
    }

    @Test
    void aWriteThatFailsAtTheStagedFileLeavesThePreviousContents(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("target.bin");
        AtomicFileWrite.write(file, "kept".getBytes(StandardCharsets.UTF_8));
        // A directory where the staged sibling belongs, so the write fails before it can reach the target.
        Files.createDirectory(directory.resolve("target.bin.tmp"));

        assertThrows(IOException.class,
                () -> AtomicFileWrite.write(file, "lost".getBytes(StandardCharsets.UTF_8)));

        assertEquals("kept", new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "a write that failed before the move never reached the file it was replacing");
    }
}
