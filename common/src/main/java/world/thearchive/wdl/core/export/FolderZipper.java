// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a save folder into a single artifact (the export, or the resume backup) with {@code java.util.zip} over a
 * {@code java.nio} walk, no new dependency. MC-free and band-agnostic. Entries are rooted at the folder name
 * ({@code <folder>/level.dat} ...), so unzipping reproduces the save folder.
 *
 * <p>Fail-soft over the openable folder: the folder is only read, never modified. The zip is written to a temporary
 * {@code .part} file in the same directory and atomically moved into place only on success, so the final name never
 * appears as a half-written, valid-looking artifact; any failure deletes the temporary file and surfaces the error.
 * {@link #zip} returns the on-disk byte total it walked.
 */
final class FolderZipper {
    private static final String TEMPORARY_PREFIX = "wdl-export-";
    private static final String TEMPORARY_SUFFIX = ".part";

    private FolderZipper() {}

    /** Zip {@code sourceFolder} into {@code target}; returns the walked on-disk byte total. */
    public static long zip(Path sourceFolder, Path target) throws IOException {
        return zip(sourceFolder, target, bytes -> {});
    }

    /**
     * Zip {@code sourceFolder} into {@code target}, reporting the cumulative archived byte total to {@code
     * onBytesZipped} after each file so a caller can drive a live progress fraction; returns the walked total.
     */
    public static long zip(Path sourceFolder, Path target, LongConsumer onBytesZipped) throws IOException {
        Path directory = target.getParent();
        Path temporaryFile = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        try {
            long walked = writeZip(sourceFolder, temporaryFile, onBytesZipped);
            move(temporaryFile, target);
            return walked;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporaryFile);
            throw e;
        }
    }

    private static long writeZip(Path sourceFolder, Path temporaryFile, LongConsumer onBytesZipped) throws IOException {
        // Root every entry at the folder name (<folder>/level.dat ...) so unzipping reproduces the save folder.
        // Derived from the folder itself rather than its parent, which is null for a filesystem root.
        Path folderName = sourceFolder.getFileName();
        final String top = folderName == null ? "" : folderName.toString();
        final long[] total = { 0 };
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporaryFile)))) {
            Files.walkFileTree(sourceFolder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || SessionLock.matches(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = sourceFolder.relativize(file).toString().replace(File.separatorChar, '/');
                    if (relative.equals("wdl/download.pending")) {
                        // The crash sentinel predates the pre-resume backup by design, so archiving it would
                        // plant a false crash signal in the zip; mirrors the restore extractor's strip.
                        return FileVisitResult.CONTINUE;
                    }
                    String entryName = top.isEmpty() ? relative : top + "/" + relative;
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    total[0] += attributes.size();
                    onBytesZipped.accept(total[0]);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return total[0];
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of the temporary file; nothing more to do if even the delete fails
        }
    }
}
