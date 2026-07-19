// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.OptionalLong;

/**
 * The on-disk byte total of a finished save folder, summed by a {@code java.nio} tree walk. MC-free and band-agnostic.
 * A walk that fails (the folder is gone or unreadable) returns {@linkplain OptionalLong#empty() no size} rather than a
 * wrong zero, so the download screen shows no size instead of a misleading one.
 */
public final class FolderSize {
    private FolderSize() {}

    /** The total bytes of every regular file under {@code folder}, recursively; empty if the walk fails. */
    public static OptionalLong onDiskSize(Path folder) {
        final long[] total = { 0 };
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !SessionLock.matches(file)) {
                        total[0] += attributes.size();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return OptionalLong.of(total[0]);
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }
}
