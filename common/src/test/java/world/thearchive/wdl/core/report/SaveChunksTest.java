// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveChunksTest {
    @Test
    void sumsAcrossDimensionsAndDropsChunklessEntries(@TempDir Path root) throws IOException {
        Path overworld = root.resolve("region");
        Path nether = root.resolve("DIM-1").resolve("region");
        Path empty = root.resolve("DIM1").resolve("region");
        writeRegionFile(overworld, "r.0.0.mca", 3);
        writeRegionFile(nether, "r.0.0.mca", 2);
        Files.createDirectories(empty);
        Map<String, Path> directories = new LinkedHashMap<>();
        directories.put("minecraft:overworld", overworld);
        directories.put("minecraft:the_nether", nether);
        directories.put("minecraft:the_end", empty);
        // phantom, no files
        directories.put("myns:region", root.resolve("dimensions").resolve("myns").resolve("region"));

        SaveChunks scan = SaveChunks.scan(directories);

        assertEquals(5, scan.total());
        assertEquals(2, scan.dimensions().size());
        assertEquals("minecraft:overworld", scan.dimensions().get(0).dimensionName());
        assertEquals(3, scan.dimensions().get(0).chunks());
        assertEquals("minecraft:the_nether", scan.dimensions().get(1).dimensionName());
        assertEquals(2, scan.dimensions().get(1).chunks());
    }

    @Test
    void emptyMapScansToZero() {
        SaveChunks scan = SaveChunks.scan(Collections.<String, Path>emptyMap());
        assertEquals(0, scan.total());
        assertTrue(scan.dimensions().isEmpty());
    }

    /** A region file whose 4 KB location header carries {@code presentChunks} non-zero entries. */
    private static void writeRegionFile(Path directory, String name, int presentChunks) throws IOException {
        Files.createDirectories(directory);
        ByteBuffer header = ByteBuffer.allocate(4096);
        for (int i = 0; i < presentChunks; i++) {
            header.putInt(i * 4, (2 + i) << 8 | 1);
        }
        Files.write(directory.resolve(name), header.array());
    }
}
