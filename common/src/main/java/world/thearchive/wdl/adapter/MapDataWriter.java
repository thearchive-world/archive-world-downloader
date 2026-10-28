// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import world.thearchive.wdl.core.AtomicFileWrite;

/**
 * The {@code data/idcounts.dat} file is the map allocator's high-water, and at 1.13.2 it is a different on-disk shape
 * than the map file: a root {@code {map: int}} written UNCOMPRESSED, with no {@code data} wrapper and no
 * {@code DataVersion}, the bytes vanilla's {@code DimensionDataStorage} writes and reads.
 */
final class MapDataWriter {
    private static final String ID_COUNTS_KEY = "idcounts";
    private static final String MAP_KEY = "map";

    private static final int DATA_VERSION = 1631;

    private MapDataWriter() {}

    public static CompoundTag serializeIdCounts(int maxId) {
        CompoundTag idCounts = new CompoundTag();
        idCounts.putInt(MAP_KEY, maxId);
        return idCounts;
    }

    /**
     * Wrap {@code dataTag} as {@code {data, DataVersion}} and gzip it to {@code dataDirectory/<key>.dat}, creating the
     * target's parent first since opening the file does not make its parents.
     */
    public static void write(Path dataDirectory, String key, Tag dataTag) throws IOException {
        Path file = dataDirectory.resolve(key + ".dat");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            NbtIo.writeCompressed(envelope(dataTag), out);
        }
    }

    /**
     * Losing this file restarts the reopened world's allocator at id 0, which overwrites archived map data, so it is
     * staged whole and atomically moved rather than truncating the destination at open.
     */
    public static void writeIdCounts(Path dataDirectory, Tag dataTag) throws IOException {
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(staged);
        NbtIo.write((CompoundTag) dataTag, out);
        out.flush();
        AtomicFileWrite.write(dataDirectory.resolve(ID_COUNTS_KEY + ".dat"), staged.toByteArray());
    }

    private static CompoundTag envelope(Tag dataTag) {
        CompoundTag envelope = new CompoundTag();
        envelope.put("data", dataTag);
        envelope.putInt("DataVersion", DATA_VERSION);
        return envelope;
    }

    public static int readIdCounts(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(ID_COUNTS_KEY + ".dat");
        if (!Files.exists(file)) {
            return -1;
        }
        CompoundTag root;
        try (InputStream input = Files.newInputStream(file)) {
            root = NbtIo.read(new DataInputStream(input));
        }
        return root.contains(MAP_KEY) ? root.getInt(MAP_KEY) : -1;
    }
}
