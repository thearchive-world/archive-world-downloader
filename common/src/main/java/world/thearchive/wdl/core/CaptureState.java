// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * Lifecycle of a capture run: {@code IDLE -> RECORDING -> SAVING -> IDLE}. A tainted-folder restore takes the
 * session-less side path {@code IDLE -> RESTORING -> IDLE} instead, so a capture and a restore can never run at once.
 */
public enum CaptureState {
    IDLE,
    RECORDING,
    SAVING,
    RESTORING
}
