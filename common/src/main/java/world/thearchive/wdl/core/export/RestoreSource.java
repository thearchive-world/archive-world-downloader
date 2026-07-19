// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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

/**
 * The clean-source discovery scan for a tainted-folder restore: the newest qualifying zip in the saves directory whose
 * name belongs to the download folder's export family. A candidate qualifies only by passing seven fail-closed rules in
 * order: its file name matches the family grammar exactly; every entry is rooted at the folder name with no dotdot
 * segment on any slash or backslash boundary; no duplicate entry names once lowercased and trailing-slash normalized;
 * no file entry path-prefixes another entry; the exact-case file folder/level.dat exists; no entry path names a
 * server-only artifact per {@link SinglePlayerTaint#entryPathIsServerArtifact}; and the exact-case record
 * folder/wdl/download.jsonl stays within {@link #MAX_RECORD_BYTES} uncompressed. Qualifying candidates order by file
 * modification time, newest first.
 *
 * <p>The scan never throws: a failure judging one candidate excludes that candidate, each exclusion logged with the
 * candidate name and failing rule at FINE, and a harness-level failure logs one WARN and reports no source.
 */
public final class RestoreSource {
    private static final Logger LOGGER = Logger.getLogger(RestoreSource.class.getName());

    /** Uncompressed cap on the record entry: an at-cap record passes, an over-cap record excludes. */
    static final long MAX_RECORD_BYTES = 8L * 1024 * 1024;

    // Ceiling on the central-directory entry count judged per candidate, read cheaply from the count field
    // without iterating. A multi-dimension world exports at most tens of thousands of files, so this is far
    // above any real export yet stops a family-named zip-bomb from freezing the gate thread with O(N) work.
    static final int MAX_ENTRIES = 1_000_000;

    private final Path zip;
    private final long size;
    private final FileTime mtime;

    private RestoreSource(Path zip, long size, FileTime mtime) {
        this.zip = zip;
        this.size = size;
        this.mtime = mtime;
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

    /**
     * The newest clean restore source for folderName among the family-named zips in savesDirectory, or empty when no
     * candidate qualifies. Never throws.
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
                if (best == null || judged.mtime.compareTo(best.mtime) > 0) {
                    best = judged;
                }
            }
            return Optional.ofNullable(best);
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "restore-source scan failed; treating as no source", e);
            return Optional.empty();
        }
    }

    /**
     * Re-stat the pinned source before it is opened: still a regular file with the same size and modification time.
     * Metadata equality only; an unreadable stat is false.
     */
    public static boolean stillIdentical(RestoreSource pinned) {
        try {
            return Files.isRegularFile(pinned.zip) && Files.size(pinned.zip) == pinned.size
                    && Files.getLastModifiedTime(pinned.zip).equals(pinned.mtime);
        } catch (IOException e) {
            return false;
        }
    }

    /** The directory's regular files whose names match the export family grammar exactly, never by prefix. */
    private static List<Path> familyCandidates(Path savesDirectory, String folderName) throws IOException {
        // <folder>.zip | <folder>_(N).zip | <folder>-pre-resume[_(N)].zip | <folder>-singleplayer[_(N)].zip
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

    /** One zip session per candidate; any throw, including from close, excludes only this candidate. */
    private static @Nullable RestoreSource judge(Path zip, String folderName) {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            return judge(zipFile, zip, folderName);
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "excluded " + zip.getFileName(), e);
            return null;
        }
    }

    /**
     * Apply the seven rules reading only from the one open zip session; null when the candidate is excluded, each
     * exclusion logged at FINE with the failing rule.
     */
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
            // Windows Path.normalize treats backslash as a separator, so extract would refuse a crafted
            // World/..\evil that a slash-only split accepts. Fold backslash to slash for this check only,
            // leaving name itself intact so a legal backslash in a filename still passes.
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
            // Membership test over every proper segment prefix, never sorted adjacency: characters like
            // '-' sort below '/', so a sibling such as x-old sits between x and x/y in sorted order and
            // hides the collision from an adjacency scan. Directory entries are harmless prefixes and
            // stay out of the set.
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
        return new RestoreSource(zip, Files.size(zip), Files.getLastModifiedTime(zip));
    }

    private static @Nullable RestoreSource excluded(Path zip, String rule) {
        LOGGER.fine("excluded " + zip.getFileName() + ": " + rule);
        return null;
    }
}
