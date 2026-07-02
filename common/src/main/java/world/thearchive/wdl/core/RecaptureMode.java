// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * How current a download keeps the world as the player records: the single knob over two orthogonal capabilities,
 * keeping the loaded area current as it changes (hot re-capture) and overwriting an already-downloaded area when the
 * player revisits it. Version-agnostic core: imports no {@code net.minecraft.*} type and stays Java-8-clean, so it is
 * cherry-pickable across era-band branches.
 *
 * <ul>
 * <li>{@link #OFF} captures each area once and keeps it as first seen, the lowest per-tick cost escape hatch.</li>
 * <li>{@link #NEARBY} keeps the area around the player current, but freezes each area once left, so an
 * already-downloaded area is preserved as first saved (the archival lever).</li>
 * <li>{@link #EVERYWHERE} additionally overwrites an already-downloaded area on revisit (current-world-wins).</li>
 * </ul>
 */
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
