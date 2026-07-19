// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import world.thearchive.wdl.core.browse.SinglePlayerTaint;

/**
 * The verified extractor mirroring {@link FolderZipper}: extracts the zip's folderName tree so that
 * targetParent.resolve(folderName) is the extracted world. Two passes over one open zip session: a validation pass
 * proving the contract first (root identity, no duplicate entry names once lowercased and trailing-slash normalized, no
 * file entry path-prefixing another entry, every file entry contained under the target folder, no entry naming a
 * server-only artifact per {@link SinglePlayerTaint#entryPathIsServerArtifact}, and the exact-case file entries
 * folder/level.dat and folder/wdl/download.jsonl present), then the extract pass, so any violation throws
 * {@link IOException} before a byte lands. Extraction materializes file entries only, skipping session.lock and
 * wdl/download.pending; directory entries never materialize, parents come from file entries. The caller owns the
 * pre-open re-stat of the source and the cleanup of a partial tree when a mid-extract failure propagates.
 */
final class FolderUnzipper {
    private FolderUnzipper() {}

    // Per-entry uncompressed ceiling: a single save file (a region .mca, level.dat) runs to tens of MB at
    // most, so this sits far above any legitimate entry yet aborts a decompression-bomb entry before it can
    // fill the staging disk. A throw here lands pre-swap, so the world is never corrupted.
    static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    static void extract(Path zip, String folderName, Path targetParent) throws IOException {
        extract(zip, folderName, targetParent, MAX_ENTRY_BYTES);
    }

    static void extract(Path zip, String folderName, Path targetParent, long maxEntryBytes)
            throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            validate(zipFile, folderName, targetParent);
            for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String rootRelative = name.substring(folderName.length() + 1);
                if (rootRelative.equals("session.lock") || rootRelative.equals("wdl/download.pending")) {
                    // The lock is transient, and a restored world must read as its last recorded health,
                    // never as a RECOVERABLE crash.
                    continue;
                }
                if (entry.getSize() > maxEntryBytes || entry.getCompressedSize() > maxEntryBytes) {
                    throw new IOException("entry size exceeds the " + maxEntryBytes + "-byte cap: " + name);
                }
                Path resolved = targetParent.resolve(name).normalize();
                Path parent = resolved.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = new CappedInputStream(zipFile.getInputStream(entry), maxEntryBytes)) {
                    Files.copy(in, resolved);
                }
            }
        }
    }

    /**
     * The fail-closed re-check of the discovery rules over the same open session, before any write: the source may have
     * been swapped since it was judged, so extraction re-proves what it relies on.
     */
    private static void validate(ZipFile zipFile, String folderName, Path targetParent) throws IOException {
        Path targetFolder = targetParent.resolve(folderName).normalize();
        Set<String> normalizedNames = new HashSet<String>();
        List<String> entryNames = new ArrayList<String>();
        List<String> fileNames = new ArrayList<String>();
        boolean levelDatPresent = false;
        boolean recordPresent = false;
        String levelDatName = folderName + "/level.dat";
        String recordName = folderName + "/wdl/download.jsonl";
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            int rootEnd = name.indexOf('/');
            if (rootEnd < 0 || !folderName.equals(name.substring(0, rootEnd))) {
                throw new IOException("entry outside the folder root: " + name);
            }
            // Windows Path.normalize treats backslash as a separator, so a POSIX extract keeps a crafted
            // World/..\evil as a literal name that the startsWith check accepts. Fold backslash to slash for
            // this check only, leaving name intact so a legal backslash in a filename still passes, matching
            // the discovery-side containment.
            for (String segment : name.replace('\\', '/').split("/")) {
                if (segment.equals("..")) {
                    throw new IOException("path containment: " + name);
                }
            }
            String withoutTrailingSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (!normalizedNames.add(withoutTrailingSlash.toLowerCase(Locale.ROOT))) {
                throw new IOException("duplicate entry name: " + name);
            }
            entryNames.add(name);
            if (!entry.isDirectory()) {
                fileNames.add(name);
                if (name.equals(levelDatName)) {
                    levelDatPresent = true;
                } else if (name.equals(recordName)) {
                    recordPresent = true;
                }
                if (!targetParent.resolve(name).normalize().startsWith(targetFolder)) {
                    throw new IOException("entry escapes the target folder: " + name);
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
                    throw new IOException("file prefix collision: " + fileName);
                }
            }
        }
        for (String name : entryNames) {
            if (SinglePlayerTaint.entryPathIsServerArtifact(name.substring(folderName.length() + 1))) {
                throw new IOException("server artifact entry: " + name);
            }
        }
        if (!levelDatPresent) {
            throw new IOException("missing file entry: " + levelDatName);
        }
        if (!recordPresent) {
            throw new IOException("missing file entry: " + recordName);
        }
    }

    /**
     * Counts uncompressed bytes delivered and fails the read past the cap, whatever size the central directory claims,
     * so a lying or absent (-1) size never lets a decompression bomb through the copy.
     */
    private static final class CappedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long delivered;

        CappedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
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
            if (delivered > maxBytes) {
                throw new IOException("entry exceeds " + maxBytes + " uncompressed bytes");
            }
        }
    }
}
