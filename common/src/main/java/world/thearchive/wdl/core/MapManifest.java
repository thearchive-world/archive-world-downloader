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
 * monotonic counter shared by every referenced map. A filled map's session-local {@code MapId} is reshuffled by a
 * server that renumbers ids per session, so resuming into a folder must re-key each map by its content, not its session
 * id; this manifest is the one identity that needs persisting (chunks and entities are already coordinate/UUID stable
 * on disk).
 *
 * <p>MC-free and dependency-free (pure {@code String}-to-{@code int} over {@code java.nio}), so the core owns it on
 * every band. The on-disk form is a line-oriented, schema-versioned file ({@code <save>/wdl/map-ids}) mirroring the
 * download report's durable-contract discipline: a header line carries the schema version and the counter high-water,
 * then one {@code <hex-sha256>\t<archiveId>} per imaged map. Reading is crash-tolerant (a torn line is skipped), and
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

    private final Map<String, Integer> idByHash;
    private int nextArchiveId;

    private MapManifest(Map<String, Integer> idByHash, int nextArchiveId) {
        this.idByHash = idByHash;
        this.nextArchiveId = nextArchiveId;
    }

    /** A fresh manifest for a new download: the archive id space starts empty at id 0. */
    public static MapManifest empty() {
        return new MapManifest(new LinkedHashMap<>(), 0);
    }

    /**
     * Raise the counter past {@code highestUsedId} so no id the folder already used can be reissued to a different
     * picture. Monotonic and idempotent: a value the counter already clears changes nothing, and -1 means no used id is
     * known. Kept separate from {@link #load(Path)} so a caller whose floor read fails can still keep the manifest it
     * parsed.
     */
    public void raiseCounterAbove(int highestUsedId) {
        nextArchiveId = Math.max(nextArchiveId, highestUsedId + 1);
    }

    /** The manifest file under {@code saveFolder}: {@code <saveFolder>/wdl/map-ids}, the one path owner. */
    public static Path pathIn(Path saveFolder) {
        return saveFolder.resolve(WDL_SUBFOLDER).resolve(MANIFEST_FILE);
    }

    /** Whether {@code saveFolder} carries a remap manifest, i.e. a prior session downloaded it with remapping on. */
    public static boolean existsIn(Path saveFolder) {
        return Files.exists(pathIn(saveFolder));
    }

    /**
     * The archive id for {@code hash}: an already-known hash returns its stable id; a new hash takes the next counter
     * id and records it. Idempotent across sources within a session and across sessions once persisted, which is what
     * defeats a renumbering server.
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

    /**
     * A fresh counter id for an imageless referenced map (chest-only or nested, no colors to hash): it routes through
     * the same monotonic counter so it can never alias a real picture's data file, but records no hash, so a re-seen
     * imageless map across a resume takes a new id (rare, monotonic, never a wrong picture).
     */
    public int allocateImageless() {
        return nextArchiveId++;
    }

    /** The next archive id this manifest would hand out (the counter high-water, persisted in the header). */
    int nextArchiveId() {
        return nextArchiveId;
    }

    /** The highest archive id assigned so far, the value written into {@code idcounts}'s {@code map}; -1 if none. */
    public int highestAssignedId() {
        return nextArchiveId - 1;
    }

    /** The number of imaged maps with a recorded hash (imageless allocations are not counted). */
    int size() {
        return idByHash.size();
    }

    /** Load the manifest at {@code file} (absent gives an empty manifest), with no on-disk crash floor applied. */
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

    /**
     * The highest {@code n} among the {@code data/map_<n>.dat} files in {@code dataDirectory}, or -1 if there are none.
     */
    public static int highestDataFileId(Path dataDirectory) throws IOException {
        if (!Files.isDirectory(dataDirectory)) {
            return -1;
        }
        try (Stream<Path> entries = Files.list(dataDirectory)) {
            int highest = -1;
            for (Path path : (Iterable<Path>) entries::iterator) {
                highest = Math.max(highest, dataFileId(path.getFileName().toString()));
            }
            return highest;
        }
    }

    /**
     * Whether resuming into {@code saveFolder} would mix map-id schemes: it holds imaged map data
     * ({@code data/map_<n>.dat}) whose scheme (archive ids when a manifest is present, original ids otherwise) differs
     * from {@code remapMapIds}. A folder with no imaged map data never mismatches. An IO failure reads as no mismatch,
     * so a bad disk read never fires a spurious warn.
     */
    public static boolean schemeMismatch(Path saveFolder, boolean remapMapIds) {
        try {
            boolean hasMapData = highestDataFileId(saveFolder.resolve(DATA_SUBFOLDER)) >= 0;
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
            // fields[0] is the write-only schema stamp: on load it is only sign-checked as a validity gate,
            // its value unused (a forward-compat marker, like a DataVersion).
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
            return; // a torn or malformed line is skipped (crash-tolerant)
        }
        try {
            idByHash.put(fields[0], Integer.parseInt(fields[1].trim()));
        } catch (NumberFormatException e) {
            // A torn trailing id is skipped
        }
    }

    private static int dataFileId(String fileName) {
        if (!fileName.startsWith("map_") || !fileName.endsWith(".dat")) {
            return -1;
        }
        String digits = fileName.substring("map_".length(), fileName.length() - ".dat".length());
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
