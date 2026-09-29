// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import org.jspecify.annotations.Nullable;

/**
 * The resolved target of a download: the save-folder name, the user-facing world name, and whether it is a fresh
 * download or a resume. The folder name is used verbatim.
 *
 * <p>{@code worldName} is the name written into a new world's level.dat: the resolved folder name, which carries the
 * {@code -YYYY-MM-DD} suffix when it is enabled. On a resume the session preserves the existing world's name rather
 * than using this.
 */
public final class DownloadTarget {
    /**
     * The surface that minted the target, deciding a late refusal's channel: the downloads screen, the deliberate
     * command/keybind flow, or the auto-download-on-join flow.
     */
    public enum Origin {
        SCREEN, FLOW_DELIBERATE, FLOW_AUTO
    }

    private final String folderName;
    private final @Nullable String worldName;
    private final DownloadMode mode;
    private final Origin origin;

    public DownloadTarget(String folderName, @Nullable String worldName, DownloadMode mode) {
        this(folderName, worldName, mode, Origin.SCREEN);
    }

    private DownloadTarget(String folderName, @Nullable String worldName, DownloadMode mode, Origin origin) {
        this.folderName = folderName;
        this.worldName = worldName;
        this.mode = mode;
        this.origin = origin;
    }

    /** The save-folder name under {@code saves/}, used verbatim (a resume targets an existing folder). */
    public String folderName() {
        return folderName;
    }

    /** The new download's level.dat name (its resolved folder name), or null to use the writer's default. */
    public @Nullable String worldName() {
        return worldName;
    }

    public DownloadMode mode() {
        return mode;
    }

    public Origin origin() {
        return origin;
    }

    /** A copy re-tagged with {@code origin}. */
    public DownloadTarget withOrigin(Origin origin) {
        return new DownloadTarget(folderName, worldName, mode, origin);
    }

    /** The refusal-channel rule: only the screen's refusals show as a toast, the flows answer in chat. */
    public static boolean refusalUsesToast(Origin origin) {
        return origin == Origin.SCREEN;
    }
}
