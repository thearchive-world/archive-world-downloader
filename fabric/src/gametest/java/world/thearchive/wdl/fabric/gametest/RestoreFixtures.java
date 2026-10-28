// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

import world.thearchive.wdl.core.export.RestoreOperation;

final class RestoreFixtures {
    static final String SESSION_LOCK = "session.lock";

    private RestoreFixtures() {}

    /**
     * Mark {@code folder} as singleplayer-opened by writing a non-empty player-data directory, the server-only artifact
     * {@link world.thearchive.wdl.core.browse.SinglePlayerTaint} classifies as tainted.
     */
    static void taint(Path folder) {
        try {
            Path playerData = folder.resolve("playerdata");
            Files.createDirectories(playerData);
            Files.write(playerData.resolve("00000000-0000-0000-0000-000000000000.dat"), new byte[] { 0 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void huskWithSessionLock(Path path) {
        deleteRecursively(path);
        try {
            Files.createDirectories(path);
            Files.write(path.resolve(SESSION_LOCK), new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void strayFile(Path path) {
        deleteRecursively(path);
        try {
            Files.write(path, new byte[] { 1 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The live folder is left absent, the state a client that quit mid-swap leaves behind and the next sweep rolls
     * back.
     */
    static Path craftTornAttempt(Path savesDirectory, String folderName) {
        try {
            Path attempt = savesDirectory.resolve(RestoreOperation.TEMPORARY_ROOT).resolve(folderName + "-1");
            Files.createDirectories(attempt.resolve("install"));
            Path aside = attempt.resolve("aside");
            Files.createDirectories(aside);
            Files.write(attempt.resolve("attempt.lock"), new byte[0]);
            Files.move(savesDirectory.resolve(folderName), aside.resolve(folderName),
                    StandardCopyOption.ATOMIC_MOVE);
            return attempt;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Craft a torn attempt whose kept-aside carries a locked session.lock, so a sweep defers it.
     */
    static LockedAside craftLockedTornAttempt(Path savesDirectory, String folderName) {
        try {
            Path attempt = savesDirectory.resolve(RestoreOperation.TEMPORARY_ROOT).resolve(folderName + "-1");
            Path asideFolder = attempt.resolve("aside").resolve(folderName);
            Files.createDirectories(asideFolder);
            Files.createDirectories(attempt.resolve("install"));
            Files.write(attempt.resolve("attempt.lock"), new byte[0]);
            Path lockFile = asideFolder.resolve(SESSION_LOCK);
            Files.write(lockFile, new byte[0]);
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new LockedAside(attempt, channel, lock);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class LockedAside implements AutoCloseable {
        private final Path attempt;
        private final FileChannel channel;
        private final FileLock lock;

        LockedAside(Path attempt, FileChannel channel, FileLock lock) {
            this.attempt = attempt;
            this.channel = channel;
            this.lock = lock;
        }

        Path attempt() {
            return attempt;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException e) {
                // Best effort.
            }
            try {
                channel.close();
            } catch (IOException e) {
                // Best effort.
            }
        }
    }
}
