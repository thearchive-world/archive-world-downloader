// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classic-MCP map {@code data/} writer: a serialized inner {@code "data"} tag wrapped as {@code {data}} and gzipped
 * to {@code data/<key>.dat} round-trips through a real compressed file with no {@code DataVersion} (there is none below
 * 1.13), the {@code data/} directory is created on demand, the {@code idcounts} tag is the band's uncompressed root
 * {@code {map: short}}, the branched {@code writeIdCounts}/{@code readIdCounts} round-trip the map high-water, and a
 * failed {@code idcounts} write leaves the id floor already on disk readable. Server-free: hand-built tags drive it (no
 * map type needed), matching the {@link LevelDatRoundTripTest} discipline.
 */
class MapDataWriterRoundTripTest {
    @Test
    void serializeIdCountsIsTheRootMapShortShape() {
        NBTTagCompound idCounts = MapDataWriter.serializeIdCounts(50);

        assertEquals(50, idCounts.hasKey("map") ? idCounts.getShort("map") : -1,
                "idcounts root is {map: maxId}");
        assertEquals(2 /* TAG_Short */, idCounts.getTagId("map"), "map is a short at the root");
    }

    @Test
    void writeWrapsDataGzipsAndRoundTrips(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data"); // does not exist yet: write() must create it
        NBTTagCompound inner = new NBTTagCompound();
        inner.setString("hello", "world");
        inner.setInteger("xCenter", 0);

        MapDataWriter.write(dataDirectory, "map_3", inner);

        Path file = dataDirectory.resolve("map_3.dat");
        assertTrue(Files.exists(file), "write() creates the data/ directory and the <key>.dat file");

        NBTTagCompound envelope;
        try (InputStream in = Files.newInputStream(file)) {
            envelope = CompressedStreamTools.readCompressed(in);
        }
        assertFalse(envelope.hasKey("DataVersion"), "below 1.13 the map file's envelope carries no DataVersion");
        NBTTagCompound back = envelope.getCompoundTag("data");
        assertEquals("world", back.getString("hello"), "the inner data tag round-trips under data/");
        assertEquals(0, back.hasKey("xCenter") ? back.getInteger("xCenter") : -1);
    }

    @Test
    void idCountsRoundTripsThroughTheBranchedReadWrite(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");

        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(7));

        assertEquals(7, MapDataWriter.readIdCounts(dataDirectory),
                "the branched writeIdCounts/readIdCounts round-trip the map high-water");
    }

    @Test
    void anIdCountsWriteThatFailsAtTheStagedFileLeavesTheFloorOnDisk(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");
        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(500));
        // A directory where the staged sibling belongs: only the staged route fails here, so this is what
        // separates it from a direct write.
        Files.createDirectory(dataDirectory.resolve("idcounts.dat.tmp"));

        assertThrows(IOException.class,
                () -> MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(900)));

        assertEquals(500, MapDataWriter.readIdCounts(dataDirectory),
                "a write that failed at the staged file never touched the floor already on disk");
    }

    @Test
    void readIdCountsReturnsTheWrittenMaxIdOrMinusOneWhenAbsent(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");
        assertEquals(-1, MapDataWriter.readIdCounts(dataDirectory), "absent idcounts reads as -1");

        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(500));

        assertEquals(500, MapDataWriter.readIdCounts(dataDirectory), "reads the map high-water back");
    }
}
