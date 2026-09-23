// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The {@code data/} writer: a serialized inner {@code "data"} tag wrapped as {@code {data, DataVersion}} and gzipped to
 * {@code data/<key>.dat} round-trips through a real compressed file, the {@code data/} directory is created on demand,
 * the {@code idcounts} file is the uncompressed root {@code {map: maxId}} vanilla 1.13.2 reads, and a failed
 * {@code idcounts} write leaves the id floor already on disk readable. Server-free: hand-built tags drive it (no map
 * type needed), matching the {@link LevelDatRoundTripTest} discipline.
 */
class MapDataWriterRoundTripTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.bootstrap();
    }

    @Test
    void serializeIdCountsIsTheRootMapMaxIdShape() {
        CompoundTag idCounts = MapDataWriter.serializeIdCounts(50);

        assertEquals(50, (idCounts.contains("map") ? idCounts.getInt("map") : -1), "the idcounts root is {map: maxId}");
    }

    @Test
    void writeWrapsDataVersionGzipsAndRoundTrips(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data"); // does not exist yet: write() must create it
        CompoundTag inner = new CompoundTag();
        inner.putString("hello", "world");
        inner.putInt("xCenter", 0);

        MapDataWriter.write(dataDirectory, "map_3", inner);

        Path file = dataDirectory.resolve("map_3.dat");
        assertTrue(Files.exists(file), "write() creates the data/ directory and the <key>.dat file");

        CompoundTag envelope;
        try (InputStream in = Files.newInputStream(file)) {
            envelope = NbtIo.readCompressed(in);
        }
        assertTrue((envelope.contains("DataVersion") ? envelope.getInt("DataVersion") : -1) > 0,
                "the {data, DataVersion} envelope carries the current data version");
        CompoundTag back = envelope.getCompound("data");
        assertEquals("world", back.getString("hello"), "the inner data tag round-trips under data/");
        assertEquals(0, (back.contains("xCenter") ? back.getInt("xCenter") : -1));
    }

    @Test
    void idCountsRoundTripsThroughWriteAndRead(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");

        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(7));

        assertEquals(7, MapDataWriter.readIdCounts(dataDirectory),
                "writeIdCounts and readIdCounts round-trip the map high-water");
    }

    @Test
    void aReopenedWorldAllocatesTheNextMapIdAboveTheWrittenHighWater(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");

        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(7));

        assertEquals(8, vanillaNextMapId(dataDirectory),
                "vanilla 1.13.2 reads data/idcounts.dat and allocates the next map id above the captured 7");
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

    /**
     * The next map id vanilla 1.13.2 allocates in the reopened world, decoded as its {@code DimensionDataStorage}
     * decodes {@code data/idcounts.dat}: a failed read leaves its counter empty, so the next id is 0.
     */
    private static int vanillaNextMapId(Path dataDirectory) {
        CompoundTag root;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(dataDirectory.resolve("idcounts.dat")))) {
            root = NbtIo.read(in);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        return (root.contains("map", 99) ? root.getInt("map") : -1) + 1;
    }
}
