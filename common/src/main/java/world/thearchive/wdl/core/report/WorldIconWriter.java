// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Writes the source server's icon bytes to {@code <saveRoot>/icon.png}, where MC reads the world-list thumbnail and the
 * report's {@code <img src="../icon.png">} resolves. The bytes are already a PNG (vanilla validates the server icon),
 * so they are written as-is. Fail-soft like every report write: a failure is caught and logged, never thrown, so it can
 * never corrupt the openable save. A convenience world artifact, not part of the machine contract.
 */
public final class WorldIconWriter {
    private static final Logger LOGGER = Logger.getLogger(WorldIconWriter.class.getName());
    private static final String ICON_FILE = "icon.png";

    private WorldIconWriter() {}

    public static Path iconFile(Path saveRoot) {
        return saveRoot.resolve(ICON_FILE);
    }

    /** Write {@code iconBytes} to {@code saveRoot/icon.png}; a null or empty array writes nothing. */
    public static void write(Path saveRoot, byte @Nullable [] iconBytes) {
        if (iconBytes == null || iconBytes.length == 0) {
            return;
        }
        try {
            Files.createDirectories(saveRoot);
            Files.write(saveRoot.resolve(ICON_FILE), iconBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "failed to write the world icon", e);
        }
    }
}
