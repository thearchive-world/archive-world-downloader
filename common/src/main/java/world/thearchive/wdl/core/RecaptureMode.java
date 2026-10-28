// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public enum RecaptureMode {
    OFF,
    NEARBY,
    EVERYWHERE;

    public boolean refreshesHotChunks() {
        return this != OFF;
    }

    public boolean overwritesRevisitedChunks() {
        return this == EVERYWHERE;
    }
}
