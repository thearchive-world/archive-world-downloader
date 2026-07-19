// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MC-free on-disk folder byte-size walk the download screen uses to size each row. */
class FolderSizeTest {
    @Test
    void sumsEveryRegularFileAcrossSubdirectories(@TempDir Path folder) throws IOException {
        Files.write(folder.resolve("level.dat"), new byte[100]);
        Path region = Files.createDirectories(folder.resolve("region"));
        Files.write(region.resolve("r.0.0.mca"), new byte[250]);
        Path wdl = Files.createDirectories(folder.resolve("wdl"));
        Files.write(wdl.resolve("download.jsonl"), new byte[42]);

        OptionalLong size = FolderSize.onDiskSize(folder);

        assertTrue(size.isPresent(), "a readable folder yields a size");
        assertEquals(392, size.getAsLong(), "the sum is every regular file's bytes, recursively");
    }

    @Test
    void anEmptyFolderIsZeroBytesNotAbsent(@TempDir Path folder) {
        OptionalLong size = FolderSize.onDiskSize(folder);

        assertTrue(size.isPresent(), "an empty but readable folder still has a (zero) size");
        assertEquals(0, size.getAsLong());
    }

    @Test
    void excludesTheTransientSessionLock(@TempDir Path folder) throws IOException {
        Files.write(folder.resolve("level.dat"), new byte[100]);
        Files.write(folder.resolve("session.lock"), new byte[3]); // transient lock marker, not world data

        OptionalLong size = FolderSize.onDiskSize(folder);

        assertTrue(size.isPresent());
        assertEquals(100, size.getAsLong(),
                "the session lock is excluded, so the size matches the export walk's byte total");
    }

    @Test
    void aWalkThatFailsRecordsNoSize(@TempDir Path folder) {
        OptionalLong size = FolderSize.onDiskSize(folder.resolve("does-not-exist"));

        assertFalse(size.isPresent(), "a failed walk records no size rather than a wrong zero");
    }
}
