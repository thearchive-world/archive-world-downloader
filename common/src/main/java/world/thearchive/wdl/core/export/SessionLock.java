// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import java.nio.file.Path;

/**
 * MC's world-session lock marker. On versions whose open level storage holds an exclusive OS lock on it (mandatory on
 * Windows), reading it during a resume backup would fail the walk.
 */
final class SessionLock {
    private static final String NAME = "session.lock";

    private SessionLock() {}

    static boolean matches(Path file) {
        Path name = file.getFileName();
        return name != null && NAME.equals(name.toString());
    }
}
