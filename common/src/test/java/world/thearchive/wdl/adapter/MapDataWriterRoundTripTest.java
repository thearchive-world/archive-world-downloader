// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The band-agnostic {@code data/} writer: a serialized inner {@code "data"} tag wrapped as {@code {data, DataVersion}}
 * and gzipped to {@code data/<key>.dat} round-trips through a real compressed file, the {@code data/} directory is
 * created on demand, the {@code idcounts} inner tag is the band-stable {@code {map: maxId}}, and a failed
 * {@code idcounts} write leaves the id floor already on disk readable. Server-free: hand-built tags drive it (no map
 * type needed), matching the {@link LevelDatRoundTripTest} discipline.
 */
class MapDataWriterRoundTripTest {
    @BeforeAll
    static void bootstrapVanilla() {
        TestRegistries.frozen(); // sets the game version NbtUtils.addCurrentDataVersion reads
    }

    @Test
    void serializeIdCountsIsTheBandStableMapMaxIdShape() {
        CompoundTag idCounts = MapDataWriter.serializeIdCounts(50);

        assertEquals(50, idCounts.getIntOr("map", -1), "idcounts inner data is {map: maxId}");
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

        CompoundTag envelope = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        assertTrue(envelope.getIntOr("DataVersion", -1) > 0,
                "the {data, DataVersion} envelope carries the current data version");
        CompoundTag back = envelope.getCompoundOrEmpty("data");
        assertEquals("world", back.getStringOr("hello", ""), "the inner data tag round-trips under data/");
        assertEquals(0, back.getIntOr("xCenter", -1));
    }

    @Test
    void idCountsRoundTripsUnderTheDataEnvelope(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");

        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(7));

        CompoundTag envelope = NbtIo.readCompressed(dataDirectory.resolve("idcounts.dat"),
                NbtAccounter.unlimitedHeap());
        assertEquals(7, envelope.getCompoundOrEmpty("data").getIntOr("map", -1),
                "data/idcounts.dat is {data:{map:7}, DataVersion}");
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

    @Test
    void readIdCountsRejectsAnOversizedFileInsteadOfAllocatingIt(@TempDir Path directory) throws IOException {
        Path dataDirectory = Files.createDirectories(directory.resolve("data"));
        // A byte array whose declared length is past the on-disk quota: the accounter trips on the declared
        // size before the array is allocated, so the tampered file itself is only a handful of bytes.
        try (DataOutputStream nbt = new DataOutputStream(new GZIPOutputStream(
                Files.newOutputStream(dataDirectory.resolve("idcounts.dat"))))) {
            nbt.writeByte(10); // root compound
            nbt.writeUTF("");
            nbt.writeByte(7); // a byte-array entry
            nbt.writeUTF("blob");
            nbt.writeInt(NbtAccounter.UNCOMPRESSED_NBT_QUOTA + 1);
        }

        assertThrows(NbtAccounterException.class, () -> MapDataWriter.readIdCounts(dataDirectory),
                "a resume read of a tampered idcounts.dat is bounded, not an unbounded allocation");
    }
}
