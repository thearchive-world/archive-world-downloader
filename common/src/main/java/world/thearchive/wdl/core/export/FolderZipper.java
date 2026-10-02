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
 * The zip is written to a temporary {@code .part} file in the same directory and atomically moved into place only on
 * success, so the final name never appears as a half-written, valid-looking artifact.
 */
final class FolderZipper {
    private FolderZipper() {}

    public static long zip(Path sourceFolder, Path target) throws IOException {
        return zip(sourceFolder, target, bytes -> {});
    }

    public static long zip(Path sourceFolder, Path target, LongConsumer onBytesZipped) throws IOException {
        Path directory = target.getParent();
        Path temporaryFile = Files.createTempFile(directory, SaveWalk.TEMPORARY_PREFIX, SaveWalk.TEMPORARY_SUFFIX);
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
        Path folderName = sourceFolder.getFileName();
        final String top = folderName == null ? "" : folderName.toString();
        final long[] total = { 0 };
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporaryFile)))) {
            Files.walkFileTree(sourceFolder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || SaveWalk.isExcluded(sourceFolder, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = sourceFolder.relativize(file).toString().replace(File.separatorChar, '/');
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
        } catch (IOException ignored) {}
    }
}
