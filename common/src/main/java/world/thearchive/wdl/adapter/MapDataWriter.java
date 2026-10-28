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
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import world.thearchive.wdl.core.AtomicFileWrite;

/**
 * The {@code data/idcounts.dat} file is the map allocator's high-water, and at this band it is a different on-disk
 * shape than the map file: a root {@code {map: short}} written UNCOMPRESSED, with no {@code data} wrapper and no
 * {@code DataVersion}, the exact bytes vanilla's {@code MapStorage.getUniqueDataId}/{@code loadIdCounts} write and
 * read.
 */
final class MapDataWriter {
    private static final String ID_COUNTS_KEY = "idcounts";
    private static final String MAP_KEY = "map";

    private MapDataWriter() {}

    public static NBTTagCompound serializeIdCounts(int maxId) {
        NBTTagCompound idCounts = new NBTTagCompound();
        idCounts.setShort(MAP_KEY, (short) maxId);
        return idCounts;
    }

    /**
     * Wrap {@code dataTag} as {@code {data}} and gzip it to {@code dataDirectory/<key>.dat}, creating the target's
     * parent first since opening the file does not make its parents.
     */
    public static void write(Path dataDirectory, String key, NBTBase dataTag) throws IOException {
        Path file = dataDirectory.resolve(key + ".dat");
        Files.createDirectories(file.getParent());
        NBTTagCompound envelope = new NBTTagCompound();
        envelope.setTag("data", dataTag);
        try (OutputStream out = Files.newOutputStream(file)) {
            CompressedStreamTools.writeCompressed(envelope, out);
        }
    }

    /**
     * Losing this file restarts the reopened world's allocator at id 0, which overwrites archived map data, so it is
     * staged whole and atomically moved rather than truncating the destination at open.
     */
    public static void writeIdCounts(Path dataDirectory, NBTBase dataTag) throws IOException {
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(staged);
        CompressedStreamTools.write((NBTTagCompound) dataTag, out);
        out.flush();
        AtomicFileWrite.write(dataDirectory.resolve(ID_COUNTS_KEY + ".dat"), staged.toByteArray());
    }

    public static int readIdCounts(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(ID_COUNTS_KEY + ".dat");
        if (!Files.exists(file)) {
            return -1;
        }
        NBTTagCompound root;
        try (InputStream input = Files.newInputStream(file)) {
            root = CompressedStreamTools.read(new DataInputStream(input));
        }
        return root.hasKey(MAP_KEY) ? root.getShort(MAP_KEY) : -1;
    }
}
