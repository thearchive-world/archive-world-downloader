// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

/**
 * How a candidate download folder relates to what is on disk, the shared decision behind the download screen's primary
 * action and the command and quick-start flows: {@link #NEW} does not yet exist (start fresh), {@link #RESUME_EXISTING}
 * already exists (the screen and quick-start flows route through the merge confirm and resume;
 * {@code /wdl start <name>} refuses it), and {@link #REFUSE_LOADED} is the currently-loaded world (refused as a
 * target). MC-free core, so the classification crosses the capture seam on every band.
 */
public enum TargetClassification {
    NEW,
    RESUME_EXISTING,
    REFUSE_LOADED
}
