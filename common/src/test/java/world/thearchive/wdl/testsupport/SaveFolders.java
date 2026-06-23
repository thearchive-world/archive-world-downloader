// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A save-folder fixture for the export tests: a {@code saves/world/} folder holding a {@code level.dat} and a
 * {@code region/r.0.0.mca} of the given bytes, so the finalize and zipper tests build the same shape.
 */
public final class SaveFolders {
    private SaveFolders() {}

    /** A {@code saves/world/} folder with a {@code level.dat} and {@code region/r.0.0.mca} of the given bytes. */
    public static Path worldFolder(Path saves, byte[] levelDat, byte[] region) throws IOException {
        Path folder = Files.createDirectories(saves.resolve("world"));
        Files.write(folder.resolve("level.dat"), levelDat);
        Files.createDirectories(folder.resolve("region"));
        Files.write(folder.resolve("region").resolve("r.0.0.mca"), region);
        return folder;
    }
}
