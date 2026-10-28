// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import world.thearchive.wdl.core.AtomicFileWrite;

final class MapDataWriter {
    private static final String ID_COUNTS_KEY = "idcounts";

    private MapDataWriter() {}

    public static CompoundTag serializeIdCounts(int maxId) {
        CompoundTag idCounts = new CompoundTag();
        idCounts.putInt("map", maxId);
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
     * Write {@code dataTag} to {@code dataDirectory/idcounts.dat} through {@link AtomicFileWrite} rather than
     * {@link #write}: losing this file restarts the reopened world's map allocator at id 0, which overwrites archived
     * map data, and that method truncates the file when it opens it.
     */
    public static void writeIdCounts(Path dataDirectory, Tag dataTag) throws IOException {
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        NbtIo.writeCompressed(envelope(dataTag), staged);
        AtomicFileWrite.write(dataDirectory.resolve(ID_COUNTS_KEY + ".dat"), staged.toByteArray());
    }

    private static CompoundTag envelope(Tag dataTag) {
        CompoundTag envelope = new CompoundTag();
        envelope.put("data", dataTag);
        envelope.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        return envelope;
    }

    public static int readIdCounts(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(ID_COUNTS_KEY + ".dat");
        if (!Files.exists(file)) {
            return -1;
        }
        CompoundTag envelope;
        try (InputStream input = Files.newInputStream(file)) {
            envelope = NbtIo.readCompressed(input);
        }
        CompoundTag data = envelope.getCompound("data");
        return data.contains("map") ? data.getInt("map") : -1;
    }
}
