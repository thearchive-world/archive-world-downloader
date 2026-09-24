// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import world.thearchive.wdl.core.AtomicFileWrite;

/**
 * Writes the map {@code data/} save surface: wraps a per-band serialized inner {@code "data"} tag (from
 * {@link MapSink#serializeMap}, or the {@code idcounts} tag below) as {@code {data, DataVersion}} and gzips it to
 * {@code data/<key>.dat}, the envelope vanilla writes for its saved data.
 */
final class MapDataWriter {
    private static final String ID_COUNTS_KEY = "idcounts";

    private MapDataWriter() {}

    /**
     * The {@code idcounts} inner {@code "data"} tag: {@code {map: maxId}}. Written so the reopened world's map
     * allocator ({@code getNextMapId} = {@code ++lastMapId}, reading this {@code "map"}) issues the next id above every
     * captured id, imaged or not, so no reopened-world craft is ever aliased to a captured map.
     */
    public static CompoundTag serializeIdCounts(int maxId) {
        CompoundTag idCounts = new CompoundTag();
        idCounts.putInt("map", maxId);
        return idCounts;
    }

    /**
     * Wrap {@code dataTag} as {@code {data, DataVersion}} and gzip it to {@code dataDirectory/<key>.dat}, creating the
     * target's parent first since opening the file does not make its parents. The key can name a subfolder (a
     * {@code maps/<id>} key), so the parent is the file's own directory, not {@code dataDirectory}. The same envelope
     * vanilla writes for every {@code SavedData}.
     */
    public static void write(Path dataDirectory, String key, Tag dataTag) throws IOException {
        Path file = dataDirectory.resolve(key + ".dat");
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(envelope(dataTag), file.toFile());
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

    /**
     * The {@code map} high-water recorded in an existing {@code data/idcounts.dat}, or -1 when there is none. Off-mode
     * has no manifest to persist the id floor across a resume, so it reconstructs the floor from this file (the only
     * durable record of an imageless id that sits above the highest imaged {@code map_<n>.dat}). Reads the
     * {@code {data:{map:int}}} envelope {@link #writeIdCounts} writes.
     */
    public static int readIdCounts(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(ID_COUNTS_KEY + ".dat");
        if (!Files.exists(file)) {
            return -1;
        }
        CompoundTag envelope = NbtIo.readCompressed(file.toFile());
        CompoundTag data = envelope.getCompound("data");
        return data.contains("map") ? data.getInt("map") : -1;
    }
}
