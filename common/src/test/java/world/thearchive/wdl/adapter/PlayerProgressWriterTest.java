// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerProgressWriterTest {
    private static final UUID UUID_A = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writesBothFilesUnderTheSaveRoot(@TempDir Path saveRoot) {
        byte[] advancements = "{\"DataVersion\":1}".getBytes();
        byte[] stats = "{\"stats\":{},\"DataVersion\":1}".getBytes();
        PlayerProgressWriter.write(saveRoot, new CapturedProgress(UUID_A, advancements, stats));

        assertArrayEquals(advancements, readAll(saveRoot.resolve("advancements").resolve(UUID_A + ".json")));
        assertArrayEquals(stats, readAll(saveRoot.resolve("stats").resolve(UUID_A + ".json")));
    }

    @Test
    void aNullBlobSkipsThatFile(@TempDir Path saveRoot) {
        PlayerProgressWriter.write(saveRoot, new CapturedProgress(UUID_A, "{}".getBytes(), null));
        assertTrue(Files.isRegularFile(saveRoot.resolve("advancements").resolve(UUID_A + ".json")));
        assertFalse(Files.exists(saveRoot.resolve("stats").resolve(UUID_A + ".json")),
                "a null stats blob writes no stats file");
    }

    @Test
    void aNullProgressIsNoop(@TempDir Path saveRoot) {
        PlayerProgressWriter.write(saveRoot, null); // disconnect-flush path
        assertFalse(Files.exists(saveRoot.resolve("advancements")));
        assertFalse(Files.exists(saveRoot.resolve("stats")));
    }

    @Test
    void anIoFailureOnOneSurfaceDoesNotThrowNorBlockTheOther(@TempDir Path saveRoot) throws Exception {
        // Pre-create the stats directory path AS A FILE so createDirectories/write throws for stats only.
        Files.createFile(saveRoot.resolve("stats"));

        PlayerProgressWriter.write(saveRoot, new CapturedProgress(UUID_A, "{}".getBytes(), "{}".getBytes()));

        assertTrue(Files.isRegularFile(saveRoot.resolve("advancements").resolve(UUID_A + ".json")),
                "fail-soft: the advancements surface still landed despite the stats IO failure");
        // And the call returned normally (no throw); reaching this line is the assertion.
    }

    @Test
    void aFailedRewriteLeavesThePriorArchivedFileIntact(@TempDir Path saveRoot) throws Exception {
        byte[] prior = "{\"stats\":{\"kept\":1},\"DataVersion\":1}".getBytes();
        PlayerProgressWriter.write(saveRoot, new CapturedProgress(UUID_A, null, prior));
        Path stats = saveRoot.resolve("stats").resolve(UUID_A + ".json");
        // A directory where the staged sibling belongs: only the staged route fails here, so this is what
        // separates it from a direct write.
        Files.createDirectory(saveRoot.resolve("stats").resolve(UUID_A + ".json.tmp"));

        PlayerProgressWriter.write(saveRoot, new CapturedProgress(UUID_A, null, "{}".getBytes()));

        assertArrayEquals(prior, readAll(stats),
                "a failed rewrite leaves the progress file a resume found on disk readable");
    }
}
