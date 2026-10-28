// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

final class Check {
    private Check() {}

    static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
