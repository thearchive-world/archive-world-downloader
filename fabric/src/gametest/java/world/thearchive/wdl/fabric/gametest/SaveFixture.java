// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class SaveFixture {
    private SaveFixture() {}

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
