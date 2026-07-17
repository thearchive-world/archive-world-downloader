// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Enumerates the chunk positions already saved in a dimension's {@code region/} folder by reading each region file's
 * location header, for seeding the coverage overlay with a resumed download's prior coverage. A region file's first 4
 * KB is a table of 1024 big-endian location entries (one per chunk in the 32x32 region); a non-zero entry means that
 * chunk has data on disk. This is a raw header read, never a {@code SimpleRegionStorage} open, so it opens no second
 * region handle (the one-writer invariant is untouched) and touches no chunk data. Fail-soft: an unreadable file or
 * directory yields partial coverage rather than aborting.
 */
public final class RegionChunkScan {
    private static final Logger LOGGER = Logger.getLogger(RegionChunkScan.class.getName());
    private static final long[] EMPTY = new long[0];
    private static final int OFFSET_TABLE_BYTES = 4096;

    private RegionChunkScan() {}

    /**
     * The {@link RegionMath#chunkAsLong} of every chunk present on disk under {@code regionDirectory} (empty if none).
     */
    public static long[] presentChunks(Path regionDirectory) {
        if (!Files.isDirectory(regionDirectory)) {
            return EMPTY;
        }
        LongArrayList positions = new LongArrayList();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDirectory, "r.*.mca")) {
            for (Path file : files) {
                readPresentChunks(file, positions);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "overlay resume scan: cannot list region directory " + regionDirectory, e);
        }
        return positions.toLongArray();
    }

    private static void readPresentChunks(Path file, LongArrayList out) {
        int @Nullable [] region = RegionMath.regionFileCoordinates(file.getFileName().toString());
        if (region == null) {
            return;
        }
        ByteBuffer header = ByteBuffer.allocate(OFFSET_TABLE_BYTES);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) == -1) {
                    break; // a truncated or empty region file: read the entries that are present
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "overlay resume scan: cannot read region header " + file, e);
            return;
        }
        header.flip();
        int slots = header.remaining() / Integer.BYTES;
        for (int index = 0; index < slots; index++) {
            if (header.getInt(index * Integer.BYTES) != 0) {
                out.add(RegionMath.chunkKeyAt(region[0], region[1], index));
            }
        }
    }
}
