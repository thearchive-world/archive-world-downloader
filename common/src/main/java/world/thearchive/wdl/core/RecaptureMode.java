// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public enum RecaptureMode {
    OFF,
    NEARBY,
    EVERYWHERE;

    /** Whether this mode keeps the loaded area current as it changes ({@link #NEARBY} and {@link #EVERYWHERE}). */
    public boolean refreshesHotChunks() {
        return this != OFF;
    }

    /** Whether this mode overwrites an already-downloaded area when the player revisits it ({@link #EVERYWHERE}). */
    public boolean overwritesRevisitedChunks() {
        return this == EVERYWHERE;
    }
}
