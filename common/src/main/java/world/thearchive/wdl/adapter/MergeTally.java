// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

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
