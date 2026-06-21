// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Stage the bytes in a temporary sibling, force them to storage, then atomically move it over the target, so a failed
 * write never leaves a half-written file where a complete one was. The {@code SYNC} is load-bearing: without it a
 * rename can reach storage ahead of the blocks it renames, leaving the target reading as zeros. Does not promise the
 * new contents survive a crash, which would also need the directory entry forced.
 *
 * <p>The staging sibling shares the target's directory, so the move can never cross a {@code FileStore} and
 * {@code ATOMIC_MOVE} needs no fallback.
 */
public final class AtomicFileWrite {
    private AtomicFileWrite() {}

    public static void write(Path file, byte[] bytes) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporaryFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE);
    }
}
