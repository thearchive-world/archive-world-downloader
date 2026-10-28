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

public final class FolderSize {
    private FolderSize() {}

    public static OptionalLong onDiskSize(Path folder) {
        final long[] total = { 0 };
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !SaveWalk.isExcluded(folder, file)) {
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
