// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.core.browse.SinglePlayerTaint;
import world.thearchive.wdl.core.report.StrictReportReader;

public final class RestoreSource {
    private static final Logger LOGGER = Logger.getLogger(RestoreSource.class.getName());

    static final long MAX_RECORD_BYTES = 8L * 1024 * 1024;

    static final int MAX_ENTRIES = 1_000_000;

    private final Path zip;
    private final long size;
    private final FileTime mtime;
    private final Instant finishedAt;

    private RestoreSource(Path zip, long size, FileTime mtime, Instant finishedAt) {
        this.zip = zip;
        this.size = size;
        this.mtime = mtime;
        this.finishedAt = finishedAt;
    }

    public Path zip() {
        return zip;
    }

    public long size() {
        return size;
    }

    public FileTime mtime() {
        return mtime;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    /**
     * The newest clean restore source for {@code folderName} among the family-named zips in {@code savesDirectory}, or
     * empty when no candidate qualifies. Never throws.
     */
    public static Optional<RestoreSource> find(Path savesDirectory, String folderName) {
        try {
            List<Path> candidates = familyCandidates(savesDirectory, folderName);
            RestoreSource best = null;
            for (Path candidate : candidates) {
                RestoreSource judged = judge(candidate, folderName);
                if (judged == null) {
                    continue;
                }
                if (best == null || judged.finishedAt.isAfter(best.finishedAt)
                        || (judged.finishedAt.equals(best.finishedAt) && judged.mtime.compareTo(best.mtime) > 0)) {
                    best = judged;
                }
            }
            return Optional.ofNullable(best);
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "restore-source scan failed; treating as no source", e);
            return Optional.empty();
        }
    }

    public static boolean stillIdentical(RestoreSource pinned) {
        try {
            return Files.isRegularFile(pinned.zip) && Files.size(pinned.zip) == pinned.size
                    && Files.getLastModifiedTime(pinned.zip).equals(pinned.mtime);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> familyCandidates(Path savesDirectory, String folderName) throws IOException {
        Pattern family = Pattern.compile(Pattern.quote(folderName)
                + "(?:(?:" + ZipName.PRE_RESUME_SUFFIX + "|" + ZipName.SINGLEPLAYER_SUFFIX
                + ")?(?:_\\((?:[2-9]|[1-9][0-9]+)\\))?)\\.zip");
        List<Path> result = new ArrayList<Path>();
        if (!Files.isDirectory(savesDirectory)) {
            return result;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(savesDirectory)) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name != null && family.matcher(name.toString()).matches() && Files.isRegularFile(entry)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /** Any throw excludes only this candidate. */
    private static @Nullable RestoreSource judge(Path zip, String folderName) {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            return judge(zipFile, zip, folderName);
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "excluded " + zip.getFileName(), e);
            return null;
        }
    }

    static @Nullable RestoreSource judge(ZipFile zipFile, Path zip, String folderName) throws IOException {
        return judge(zipFile, zip, folderName, MAX_ENTRIES);
    }

    static @Nullable RestoreSource judge(ZipFile zipFile, Path zip, String folderName, int maxEntries)
            throws IOException {
        if (zipFile.size() > maxEntries) {
            return excluded(zip, "entry count");
        }
        Set<String> normalizedNames = new HashSet<String>();
        List<String> entryNames = new ArrayList<String>();
        List<String> fileNames = new ArrayList<String>();
        boolean levelDatPresent = false;
        ZipEntry recordEntry = null;
        String levelDatName = folderName + "/level.dat";
        String recordName = folderName + "/wdl/download.jsonl";
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            int rootEnd = name.indexOf('/');
            if (rootEnd < 0 || !folderName.equals(name.substring(0, rootEnd))) {
                return excluded(zip, "root identity");
            }
            // Extract would refuse a crafted World/..\evil that a slash-only split accepts.
            for (String segment : name.replace('\\', '/').split("/")) {
                if (segment.equals("..")) {
                    return excluded(zip, "path containment");
                }
            }
            String withoutTrailingSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (!normalizedNames.add(withoutTrailingSlash.toLowerCase(Locale.ROOT))) {
                return excluded(zip, "duplicate entry name");
            }
            entryNames.add(name);
            if (!entry.isDirectory()) {
                fileNames.add(name);
                if (name.equals(levelDatName)) {
                    levelDatPresent = true;
                } else if (name.equals(recordName)) {
                    recordEntry = entry;
                }
            }
        }
        Set<String> normalizedFileNames = new HashSet<String>();
        for (String fileName : fileNames) {
            normalizedFileNames.add(fileName.toLowerCase(Locale.ROOT));
        }
        for (String fileName : fileNames) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            // Directory entries are harmless prefixes and stay out of the set.
            for (int cut = lower.indexOf('/'); cut >= 0; cut = lower.indexOf('/', cut + 1)) {
                if (normalizedFileNames.contains(lower.substring(0, cut))) {
                    return excluded(zip, "file prefix collision");
                }
            }
        }
        if (!levelDatPresent) {
            return excluded(zip, "save shape");
        }
        for (String name : entryNames) {
            if (SinglePlayerTaint.entryPathIsServerArtifact(name.substring(folderName.length() + 1))) {
                return excluded(zip, "server artifact content");
            }
        }
        if (recordEntry == null) {
            return excluded(zip, "record missing");
        }
        if (recordEntry.getSize() > MAX_RECORD_BYTES || recordEntry.getCompressedSize() > MAX_RECORD_BYTES) {
            return excluded(zip, "record size cap");
        }
        Optional<Instant> finishedAt;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new CappedInputStream(zipFile.getInputStream(recordEntry)), StandardCharsets.UTF_8))) {
            finishedAt = StrictReportReader.latestFinishedAt(reader);
        }
        if (!finishedAt.isPresent()) {
            return excluded(zip, "record finishedAt");
        }
        return new RestoreSource(zip, Files.size(zip), Files.getLastModifiedTime(zip), finishedAt.get());
    }

    private static @Nullable RestoreSource excluded(Path zip, String rule) {
        LOGGER.fine("excluded " + zip.getFileName() + ": " + rule);
        return null;
    }

    /**
     * Counts uncompressed bytes delivered and fails the read past {@code MAX_RECORD_BYTES}, whatever sizes the central
     * directory claims, so a lying size never lets a decompression bomb through.
     */
    private static final class CappedInputStream extends FilterInputStream {
        private long delivered;

        CappedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result >= 0) {
                count(1);
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int readCount = in.read(buffer, offset, length);
            if (readCount > 0) {
                count(readCount);
            }
            return readCount;
        }

        private void count(int bytes) throws IOException {
            delivered += bytes;
            if (delivered > MAX_RECORD_BYTES) {
                throw new IOException("record entry exceeds " + MAX_RECORD_BYTES + " uncompressed bytes");
            }
        }
    }
}
