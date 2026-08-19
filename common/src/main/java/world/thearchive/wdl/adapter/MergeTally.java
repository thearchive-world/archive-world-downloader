// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

/**
 * The outcome of one per-chunk container merge: how many stash entries folded in ({@code merged}) and how many were
 * lost to a throwing merge ({@code failed}). A no-match is neither, so it counts in neither field. Shared by
 * {@link ContainerMerge} and {@link EntityContainerMerge}.
 */
final class MergeTally {
    private final int merged;
    private final int failed;

    MergeTally(int merged, int failed) {
        this.merged = merged;
        this.failed = failed;
    }

    int merged() {
        return merged;
    }

    int failed() {
        return failed;
    }
}
