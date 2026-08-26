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
 * Writes the classic-MCP map {@code data/} save surface. A {@code data/map_<id>.dat} file wraps a per-band serialized
 * inner {@code "data"} tag (from {@link MapSink#serializeMap}) as {@code {data}} and gzips it, the envelope vanilla's
 * {@code MapStorage} writes for a map file; below 1.13 that envelope carries no {@code DataVersion}.
 *
 * <p>The {@code data/idcounts.dat} file is the map allocator's high-water, and at this band it is a different on-disk
 * shape than the map file: a root {@code {map: short}} written UNCOMPRESSED, with no {@code data} wrapper and no
 * {@code DataVersion}, the exact bytes vanilla's {@code MapStorage.getUniqueDataId}/{@code loadIdCounts} write and
 * read. The write ({@link #writeIdCounts}), the read ({@link #readIdCounts}) and the serialize
 * ({@link #serializeIdCounts}) are branched together: a write-only branch would leave {@code readIdCounts} expecting
 * the parent band's gzip {@code {data:{map:int}}} envelope, throwing on the 1.12.2 file, restarting the id floor and
 * overwriting archived map data.
 */
final class MapDataWriter {
    private static final String ID_COUNTS_KEY = "idcounts";
    private static final String MAP_KEY = "map";

    private MapDataWriter() {}

    /**
     * The idcounts root tag {@code {map: maxId}}, with {@code map} a short (the map's ItemStack-metadata type). Written
     * so the reopened world's allocator ({@code getUniqueDataId}, reading this {@code "map"}) issues the next id above
     * every captured id, imaged or not, so no reopened-world craft is ever aliased to a captured map.
     */
    public static NBTTagCompound serializeIdCounts(int maxId) {
        NBTTagCompound idCounts = new NBTTagCompound();
        idCounts.setShort(MAP_KEY, (short) maxId);
        return idCounts;
    }

    /**
     * Wrap {@code dataTag} as {@code {data}} and gzip it to {@code dataDirectory/<key>.dat}, creating the target's
     * parent first since {@code CompressedStreamTools.writeCompressed} opens the file without making parents. The key
     * can name a subfolder, so the parent is the file's own directory. The same envelope vanilla's {@code MapStorage}
     * writes for a map file, minus the {@code DataVersion} that does not exist below 1.13.
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
     * Write the idcounts root tag to {@code dataDirectory/idcounts.dat} through {@link AtomicFileWrite}, UNCOMPRESSED
     * via the classic MCP {@code CompressedStreamTools.write} (the uncompressed form; {@code writeCompressed} is the
     * gzip one). Losing this file restarts the reopened world's allocator at id 0, which overwrites archived map data,
     * so it is staged whole and atomically moved rather than truncating the destination at open.
     */
    public static void writeIdCounts(Path dataDirectory, NBTBase dataTag) throws IOException {
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(staged);
        CompressedStreamTools.write((NBTTagCompound) dataTag, out);
        out.flush();
        AtomicFileWrite.write(dataDirectory.resolve(ID_COUNTS_KEY + ".dat"), staged.toByteArray());
    }

    /**
     * The {@code map} high-water recorded in an existing {@code data/idcounts.dat}, or -1 when there is none. Off-mode
     * has no manifest to persist the id floor across a resume, so it reconstructs the floor from this file (the only
     * durable record of an imageless id that sits above the highest imaged {@code map_<n>.dat}). Reads the same
     * UNCOMPRESSED root {@code {map: short}} {@link #writeIdCounts} writes, branched in lockstep with the write so the
     * resume read never faults on the 1.12.2 file.
     */
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
