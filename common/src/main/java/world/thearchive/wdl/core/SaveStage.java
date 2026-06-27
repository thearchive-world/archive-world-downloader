// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The phase a running save is in, surfaced for the HUD's finalization bar. The bar advances through these in turn, each
 * with its own fraction; a phase with no work is skipped. {@link #NONE} is the idle value the controller returns unless
 * a save is draining.
 */
public enum SaveStage {
    NONE,
    WRITING_CHUNKS,
    WRITING_MAPS,
    COMPRESSING
}
