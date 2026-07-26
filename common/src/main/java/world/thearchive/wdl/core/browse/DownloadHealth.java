// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

/**
 * The health of a wdl-managed download, derived from its completion record. {@link #COMPLETE} finished cleanly (show
 * its size and summary); {@link #PARTIAL} finished but lost some content to a write failure (show the summary plus a
 * Partial chip in place of the size); {@link #RECOVERABLE} has no completion record, only a live crash sentinel (show a
 * Recover chip in place of the size, omit the summary). Any state can be resumed by selecting the row; the Recover chip
 * is the recoverable-only shortcut for a verbatim resume.
 */
public enum DownloadHealth {
    COMPLETE,
    PARTIAL,
    RECOVERABLE
}
