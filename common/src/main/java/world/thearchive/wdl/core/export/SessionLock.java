// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.nio.file.Path;

/**
 * MC's transient world-session lock marker. Every export and size walk skips it: it is not world data, and on versions
 * whose open level storage holds an exclusive OS lock on it (mandatory on Windows), reading it during a resume backup
 * would fail the walk. Skipping it also keeps the size total matching the export walk.
 */
final class SessionLock {
    private static final String NAME = "session.lock";

    private SessionLock() {}

    static boolean matches(Path file) {
        Path name = file.getFileName();
        return name != null && NAME.equals(name.toString());
    }
}
