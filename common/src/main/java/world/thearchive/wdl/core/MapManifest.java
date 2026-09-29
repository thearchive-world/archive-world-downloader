// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The persisted translation table from a filled map's content hash ({@link MapHash}) to a stable archive id, plus one
 * counter shared by every referenced map. Every contract of this class assumes that counter stays below
 * {@code Integer.MAX_VALUE}, past which the int wraps.
 *
 * <p>The on-disk form is a line-oriented, schema-versioned file ({@code <save>/wdl/map-ids}): a header line carries the
 * schema version and the counter high-water, then one {@code <hex-sha256>\t<archiveId>} per recorded hash.
 * {@link #save(Path)} writes via a temporary sibling and an atomic move so a torn write leaves the prior manifest
 * intact.
 */
public final class MapManifest {
    /** A bump marks a new on-disk shape; add a migrator and a round-trip test for a non-read-compatible change. */
    static final int SCHEMA_VERSION = 1;

    private static final String SEPARATOR = "\t";
    private static final String WDL_SUBFOLDER = "wdl";
    private static final String MANIFEST_FILE = "map-ids";
    private static final String DATA_SUBFOLDER = "data";
    private static final String FLAT_MAP_PREFIX = "map_";
    private static final String MAPS_SUBFOLDER = "maps";
    private static final String NAMESPACE_SUBFOLDER = "minecraft";

    private final Map<String, Integer> idByHash;
    private int nextArchiveId;

    private MapManifest(Map<String, Integer> idByHash, int nextArchiveId) {
        this.idByHash = idByHash;
        this.nextArchiveId = nextArchiveId;
    }

    /** A fresh manifest: the archive id space starts empty at id 0. */
    public static MapManifest empty() {
        return new MapManifest(new LinkedHashMap<>(), 0);
    }

    /**
     * Raise the counter past {@code highestUsedId}, when {@code highestUsedId} is below {@code Integer.MAX_VALUE}, so
     * no id the folder already used can be reissued to a different picture. Monotonic and idempotent: a value the
     * counter already clears changes nothing, and -1 means no used id is known. Kept separate from {@link #load(Path)}
     * so a caller whose floor read fails can still keep the manifest it parsed.
     */
    public void raiseCounterAbove(int highestUsedId) {
        nextArchiveId = Math.max(nextArchiveId, highestUsedId + 1);
    }

    /** The manifest file under {@code saveFolder}: {@code <saveFolder>/wdl/map-ids}, the one path owner. */
    public static Path pathIn(Path saveFolder) {
        return saveFolder.resolve(WDL_SUBFOLDER).resolve(MANIFEST_FILE);
    }

    public static boolean existsIn(Path saveFolder) {
        return Files.exists(pathIn(saveFolder));
    }

    /**
     * The archive id for {@code hash}: an already-known hash returns its stable id; a new hash takes the next counter
     * id and records it.
     */
    public int lookupOrInsert(String hash) {
        Integer existing = idByHash.get(hash);
        if (existing != null) {
            return existing;
        }
        int id = nextArchiveId++;
        idByHash.put(hash, id);
        return id;
    }

    public int allocateImageless() {
        return nextArchiveId++;
    }

    int nextArchiveId() {
        return nextArchiveId;
    }

    /** The counter less one. */
    public int highestAssignedId() {
        return nextArchiveId - 1;
    }

    int size() {
        return idByHash.size();
    }

    public static MapManifest load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return empty();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return empty();
        }
        int counter = parseHeaderCounter(lines.get(0));
        if (counter < 0) {
            return empty(); // an unreadable header means a corrupt manifest; resume as "every map is new"
        }
        Map<String, Integer> idByHash = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            parseEntry(lines.get(i), idByHash);
        }
        return new MapManifest(idByHash, counter);
    }

    public static int highestDataFileId(Path dataDirectory) throws IOException {
        int flat = highestMatching(dataDirectory, FLAT_MAP_PREFIX);
        int namespaced = highestMatching(dataDirectory.resolve(MAPS_SUBFOLDER), "");
        return Math.max(flat, namespaced);
    }

    private static int highestMatching(Path directory, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return -1;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            int highest = -1;
            for (Path path : (Iterable<Path>) entries::iterator) {
                highest = Math.max(highest, dataFileId(path.getFileName().toString(), prefix));
            }
            return highest;
        }
    }

    public static boolean schemeMismatch(Path saveFolder, boolean remapMapIds) {
        try {
            Path data = saveFolder.resolve(DATA_SUBFOLDER);
            boolean hasMapData = highestDataFileId(data) >= 0
                    || highestDataFileId(data.resolve(NAMESPACE_SUBFOLDER)) >= 0;
            return hasMapData && existsIn(saveFolder) != remapMapIds;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Write the manifest to {@code file} via a temporary sibling and an atomic move; entries are ordered by id. */
    public void save(Path file) throws IOException {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(idByHash.entrySet());
        entries.sort((left, right) -> Integer.compare(left.getValue(), right.getValue()));
        StringBuilder out = new StringBuilder();
        out.append(SCHEMA_VERSION).append(SEPARATOR).append(nextArchiveId).append('\n');
        for (Map.Entry<String, Integer> entry : entries) {
            out.append(entry.getKey()).append(SEPARATOR).append(entry.getValue()).append('\n');
        }
        AtomicFileWrite.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int parseHeaderCounter(String header) {
        String[] fields = header.split(SEPARATOR, -1);
        if (fields.length < 2) {
            return -1;
        }
        try {
            // fields[0] is the write-only schema stamp: on load it is only sign-checked as a validity gate, its value
            // unused.
            if (Integer.parseInt(fields[0].trim()) < 0) {
                return -1;
            }
            return Integer.parseInt(fields[1].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void parseEntry(String line, Map<String, Integer> idByHash) {
        if (line.trim().isEmpty()) {
            return;
        }
        String[] fields = line.split(SEPARATOR, -1);
        if (fields.length != 2 || fields[0].isEmpty()) {
            return;
        }
        try {
            idByHash.put(fields[0], Integer.parseInt(fields[1].trim()));
        } catch (NumberFormatException e) {
            // An id that does not parse skips the line
        }
    }

    private static int dataFileId(String fileName, String prefix) {
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".dat")) {
            return -1;
        }
        String digits = fileName.substring(prefix.length(), fileName.length() - ".dat".length());
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
