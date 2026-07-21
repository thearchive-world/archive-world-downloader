// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldIconWriterTest {
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

    @Test
    void writesTheBytesToIconPng(@TempDir Path directory) throws IOException {
        WorldIconWriter.write(directory, PNG);

        Path icon = directory.resolve("icon.png");
        assertTrue(Files.exists(icon));
        assertArrayEquals(PNG, Files.readAllBytes(icon));
    }

    @Test
    void writesNothingForNullOrEmptyBytes(@TempDir Path directory) {
        WorldIconWriter.write(directory, null);
        WorldIconWriter.write(directory, new byte[0]);
        assertFalse(Files.exists(directory.resolve("icon.png")), "no icon file for absent bytes");
    }

    @Test
    void isFailSoftWhenTheRootIsRegularFile(@TempDir Path directory) throws IOException {
        Path regularFile = directory.resolve("blocker");
        Files.write(regularFile, new byte[] { 1 }); // a regular file where a save directory is expected
        assertDoesNotThrow(() -> WorldIconWriter.write(regularFile, PNG));
    }
}
