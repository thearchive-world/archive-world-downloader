// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

/**
 * Minimal assertion for the client game tests: a failed check throws, which the Fabric client gametest framework
 * reports as a failed run. Kept dependency-free (no JUnit on the gametest classpath) and naming a concrete divergence
 * in every message, so a red run points at the regression.
 */
final class Check {
    private Check() {}

    static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
