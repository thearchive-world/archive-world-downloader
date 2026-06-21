// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The generator the downloaded world uses for its un-captured surroundings. {@link #VOID} is the honest default:
 * superflat all-air, so the download shows only the captured area against empty void. {@link #DEFAULT} and
 * {@link #FLAT} generate terrain in the gaps between captured chunks; that terrain is freshly generated, not the
 * server's real land (the server seed is not recoverable from a client), so both are an off-by-default opt-in. The
 * captured chunks are always the real ones regardless of this choice.
 */
public enum WorldType {
    VOID,
    DEFAULT,
    FLAT;

    /**
     * Whether this generator needs the vanilla worldgen registries reconstructed client-side (a multiplayer client is
     * never sent them). {@link #VOID} builds from the synced client registries and does not; {@link #DEFAULT} and
     * {@link #FLAT} do. The single source for both the level-data generator branch and the off-thread warm gate, so the
     * two cannot disagree and silently re-freeze the render thread.
     */
    public boolean needsWorldgenReconstruction() {
        return this != VOID;
    }
}
