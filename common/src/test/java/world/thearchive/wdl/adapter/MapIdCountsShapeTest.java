// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The idcounts byte-check gate: at this band {@code data/idcounts.dat} is the exact shape vanilla's {@code MapStorage}
 * writes, a root {@code {map: short}} written UNCOMPRESSED with no {@code data} wrapper and no {@code DataVersion}. A
 * regression to the parent band's gzip {@code {data:{map:int}, DataVersion}} envelope fails the gzip-magic arm (a) and
 * the root-shape arm (b), which is the archived {@code map_0.dat} overwrite this gate exists to catch: an allocator
 * reading a floor of 0 off the wrong file re-issues captured map ids.
 */
class MapIdCountsShapeTest {
    @Test
    void idCountsDatIsUncompressedRootShort(@TempDir Path directory) throws IOException {
        Path dataDirectory = directory.resolve("data");
        MapDataWriter.writeIdCounts(dataDirectory, MapDataWriter.serializeIdCounts(50));
        Path idcountsDat = dataDirectory.resolve("idcounts.dat");

        byte[] raw = Files.readAllBytes(idcountsDat);
        // (a) uncompressed: not a gzip stream (gzip magic is 0x1f 0x8b)
        assertFalse(raw.length >= 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b,
                "1.12.2 idcounts.dat must be uncompressed, not gzip");
        NBTTagCompound root = CompressedStreamTools.read(
                new DataInputStream(new ByteArrayInputStream(raw)));
        // (b) root {map: short}, no data wrapper, no DataVersion
        assertEquals(2 /* TAG_Short */, root.getTagId("map"), "map must be a short at the root");
        assertFalse(root.hasKey("data"), "1.12.2 idcounts has no data wrapper");
        assertFalse(root.hasKey("DataVersion"), "1.12.2 idcounts has no DataVersion");
    }
}
