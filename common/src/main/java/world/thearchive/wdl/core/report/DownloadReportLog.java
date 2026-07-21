// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The append-only machine record: one compact JSON object per completed download in {@code download.jsonl}, carrying
 * the identity, the settings diff, the server/software environment, the frozen counts, the finish instant, and the
 * clean-finish status. A separate {@code download.pending} sentinel holds the begin-time record of an unfinished
 * download; its presence with no matching completed line marks an interrupted download. Reading is crash-tolerant: a
 * torn trailing line is skipped silently (a half-written last line is expected after a JVM crash), and a malformed line
 * elsewhere is warned and skipped.
 *
 * <p>The version sits first on every line so it is readable by reading one line, not the whole document. The core stays
 * MC-free and dependency-free, so the one diagnostic uses {@code java.util.logging} (the JDK logger) rather than the MC
 * slf4j logger the adapter layers use.
 */
public final class DownloadReportLog {
    private static final Logger LOGGER = Logger.getLogger(DownloadReportLog.class.getName());

    /**
     * A bump marks a new on-disk shape; a migrator plus a round-trip test are added only for a non-read-compatible
     * change. The format is unreleased, so it starts at version 1.
     */
    static final int SCHEMA_VERSION = 1;

    private static final String KEY_VERSION = "v";
    private static final String KEY_ID = "id";
    private static final String STATUS_COMPLETE = "complete";
    private static final String STATUS_PARTIAL = "partial";
    private static final String SETTING_PREFIX = "s.";
    private static final String DIMENSION_PREFIX = "d.";
    private static final String SAVE_DIMENSION_PREFIX = "sd.";

    private DownloadReportLog() {}

    /** The begin-time record written into the pending sentinel: identity, environment, settings. */
    public static String pendingLine(DownloadIdentity identity, @Nullable ReportEnvironment environment,
            Map<String, String> settings) {
        return Json.writeObject(beginFields(identity, environment, settings));
    }

    /**
     * One completed download: the begin-time record plus the frozen session counts, the frozen in-save chunk totals
     * ({@code saveChunks} with the {@code sd.} per-dimension breakdown), and the clean-finish status.
     */
    public static String completedLine(DownloadIdentity identity, @Nullable ReportEnvironment environment,
            Map<String, String> settings, Instant finishedAt, DownloadCounts counts, SaveChunks saveChunks,
            boolean clean) {
        Map<String, @Nullable Object> fields = beginFields(identity, environment, settings);
        fields.put("finishedAt", DateTimeFormatter.ISO_INSTANT.format(finishedAt));
        fields.put("status", clean ? STATUS_COMPLETE : STATUS_PARTIAL);
        fields.put("chunks", counts.chunks());
        fields.put("entities", counts.entities());
        fields.put("containers", counts.containers());
        fields.put("saveChunks", saveChunks.total());
        for (DimensionChunks dimension : counts.dimensions()) {
            fields.put(DIMENSION_PREFIX + dimension.dimensionName(), dimension.chunks());
        }
        for (DimensionChunks dimension : saveChunks.dimensions()) {
            fields.put(SAVE_DIMENSION_PREFIX + dimension.dimensionName(), dimension.chunks());
        }
        return Json.writeObject(fields);
    }

    private static Map<String, @Nullable Object> beginFields(DownloadIdentity identity,
            @Nullable ReportEnvironment environment, Map<String, String> settings) {
        Map<String, @Nullable Object> fields = new LinkedHashMap<>();
        fields.put(KEY_VERSION, SCHEMA_VERSION);
        fields.put(KEY_ID, identity.id());
        fields.put("startedAt", DateTimeFormatter.ISO_INSTANT.format(identity.startedAt()));
        fields.put("downloaderName", identity.downloaderName());
        fields.put("downloaderUuid", identity.downloaderUuid());
        fields.put("sourceAddress", identity.sourceAddress());
        fields.put("sourceName", identity.sourceName());
        fields.put("sourceMotd", identity.sourceMotd());
        fields.put("loaderName", identity.loaderName());
        fields.put("loaderVersion", identity.loaderVersion());
        fields.put("downloadName", identity.downloadName());
        fields.put("sourceKind", identity.sourceKind());
        if (environment != null) {
            fields.put("serverBrand", environment.serverBrand());
            fields.put("simulationDistance", environment.simulationDistance());
            fields.put("dimensionName", environment.dimensionName());
            fields.put("minecraftVersion", environment.minecraftVersion());
            fields.put("modVersion", environment.modVersion());
        }
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            fields.put(SETTING_PREFIX + entry.getKey(), entry.getValue());
        }
        return fields;
    }

    /** Append one line (newline-terminated), creating the file and its parent directory if absent. */
    public static void append(Path machineFile, String line) throws IOException {
        Path parent = machineFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(machineFile, (line + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The sessions for one download folder, resolving its {@code wdl/} record and sentinel (the screen reads this). */
    public static List<DownloadSession> readDownloads(Path saveRoot) throws IOException {
        return readDownloads(DownloadReportStore.machineFile(saveRoot), DownloadReportStore.pendingFile(saveRoot));
    }

    /** Completed downloads (one per jsonl line) plus, when present and not already completed, the pending one. */
    static List<DownloadSession> readDownloads(Path machineFile, @Nullable Path pendingFile)
            throws IOException {
        List<DownloadSession> downloads = new ArrayList<>();
        Set<String> completedIds = new HashSet<>();
        if (Files.exists(machineFile)) {
            List<String> lines = Files.readAllLines(machineFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                DownloadSession session = parseRecord(lines, i);
                if (session != null) {
                    downloads.add(session);
                    completedIds.add(session.identity().id());
                }
            }
        }
        if (pendingFile != null && Files.exists(pendingFile)) {
            DownloadSession pending = parsePending(pendingFile);
            if (pending != null && !completedIds.contains(pending.identity().id())) {
                downloads.add(pending); // the in-progress / interrupted download, most recent
            }
        }
        return downloads;
    }

    private static @Nullable DownloadSession parseRecord(List<String> lines, int index) {
        String line = lines.get(index);
        if (line.trim().isEmpty()) {
            return null;
        }
        try {
            return readRecord(Json.parseObject(line));
        } catch (IllegalArgumentException e) {
            if (index < lines.size() - 1) {
                LOGGER.warning("skipping a malformed download-report record at line " + (index + 1));
            }
            return null; // a torn trailing line is expected after a crash, so it is silent
        }
    }

    private static @Nullable DownloadSession parsePending(Path pendingFile) throws IOException {
        List<String> lines = Files.readAllLines(pendingFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return null;
        }
        try {
            return readRecord(Json.parseObject(lines.get(0)));
        } catch (IllegalArgumentException e) {
            return null; // a torn pending sentinel is treated as absent
        }
    }

    /** One parsed object to one download; completion is present iff the object carries {@code finishedAt}. */
    private static DownloadSession readRecord(Map<String, @Nullable Object> object) {
        DownloadIdentity identity = readIdentity(object);
        ReportEnvironment environment = readEnvironment(object);
        Map<String, String> settings = readSettings(object);
        if (object.containsKey("finishedAt")) {
            boolean clean = STATUS_COMPLETE.equals(stringValue(object, "status"));
            DownloadCounts counts = new DownloadCounts(intValue(object, "chunks"), intValue(object, "entities"),
                    intValue(object, "containers"), readDimensions(object, DIMENSION_PREFIX));
            SaveChunks saveChunks = new SaveChunks(intValue(object, "saveChunks"),
                    readDimensions(object, SAVE_DIMENSION_PREFIX));
            return new DownloadSession(identity, settings, environment, true, clean,
                    instantValue(object, "finishedAt"), counts, saveChunks);
        }
        return new DownloadSession(identity, settings, environment, false, false, null, null, null);
    }

    private static @Nullable ReportEnvironment readEnvironment(Map<String, @Nullable Object> object) {
        if (!object.containsKey("dimensionName") && !object.containsKey("minecraftVersion")) {
            return null; // a record with no environment fields has none
        }
        return new ReportEnvironment(stringValue(object, "serverBrand"),
                intValue(object, "simulationDistance"), stringValue(object, "dimensionName"),
                stringValue(object, "minecraftVersion"), stringValue(object, "modVersion"));
    }

    private static DownloadIdentity readIdentity(Map<String, @Nullable Object> object) {
        return new DownloadIdentity(stringValue(object, KEY_ID), instantValue(object, "startedAt"),
                stringValue(object, "downloaderName"), stringValue(object, "downloaderUuid"),
                stringValue(object, "sourceAddress"), stringValue(object, "sourceName"),
                stringValue(object, "sourceMotd"), stringValue(object, "loaderName"),
                stringValue(object, "loaderVersion"), stringValue(object, "downloadName"),
                stringValue(object, "sourceKind"));
    }

    private static Map<String, String> readSettings(Map<String, @Nullable Object> object) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Map.Entry<String, @Nullable Object> entry : object.entrySet()) {
            if (entry.getKey().startsWith(SETTING_PREFIX) && entry.getValue() instanceof String) {
                settings.put(entry.getKey().substring(SETTING_PREFIX.length()), (String) entry.getValue());
            }
        }
        return settings;
    }

    private static List<DimensionChunks> readDimensions(Map<String, @Nullable Object> object, String prefix) {
        List<DimensionChunks> dimensions = new ArrayList<>();
        for (Map.Entry<String, @Nullable Object> entry : object.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof Number) {
                dimensions.add(new DimensionChunks(entry.getKey().substring(prefix.length()),
                        ((Number) entry.getValue()).intValue()));
            }
        }
        return dimensions;
    }

    private static String stringValue(Map<String, @Nullable Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static int intValue(Map<String, @Nullable Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Instant instantValue(Map<String, @Nullable Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof String) {
            try {
                return Instant.parse((String) value);
            } catch (RuntimeException e) {
                return Instant.EPOCH;
            }
        }
        return Instant.EPOCH;
    }
}
