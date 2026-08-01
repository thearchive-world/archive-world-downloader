// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Resets a test's on-disk save state before it records: the client saves directory persists across suite runs in the
 * same run directory, so a prior run's completed report, data files, and backup zips would otherwise satisfy or falsify
 * this run's on-disk assertions (a stale completed record reads as "not mid-capture"; a stale backup zip is read in
 * place of the fresh one, which takes the next free name).
 */
final class SaveFixture {
    private SaveFixture() {}

    /** Delete {@code savesDirectory/folderName} recursively plus every zip derived from that folder name. */
    static void reset(Path savesDirectory, String folderName) {
        deleteRecursively(savesDirectory.resolve(folderName));
        if (!Files.isDirectory(savesDirectory)) {
            return;
        }
        try (Stream<Path> siblings = Files.list(savesDirectory)) {
            siblings.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith(folderName) && name.endsWith(".zip");
            }).forEach(SaveFixture::deleteRecursively);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
