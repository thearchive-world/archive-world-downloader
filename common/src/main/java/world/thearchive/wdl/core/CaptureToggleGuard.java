// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The consequence-naming guard a settings change warrants. Because the screen commits silently on close, a
 * download-harming edit is confirmed at the moment of the change rather than at close. {@link #CAPTURE} is the hard
 * data-loss confirm for turning either of the two core capture toggles off; {@link #RECAPTURE} is the milder staleness
 * confirm for reducing the recapture mode's freshness (a multi-state transition, see {@link #whenReducingRecapture});
 * {@link #NONE} is every benign change, which takes no confirm so over-confirming never trains click-through.
 */
public enum CaptureToggleGuard {
    NONE,
    CAPTURE,
    RECAPTURE;

    /**
     * The confirm warranted by turning the boolean toggle {@code key} off: a hard {@link #CAPTURE} confirm when
     * disabling the option destroys already-captured data ({@link ConfigOption#losesDataOnDisable()}), else
     * {@link #NONE}. The recapture mode is not a boolean toggle; its milder {@link #RECAPTURE} reduction confirm is
     * decided by {@link #whenReducingRecapture}.
     */
    public static CaptureToggleGuard whenDisabling(String key) {
        return ConfigSchema.option(key).losesDataOnDisable() ? CAPTURE : NONE;
    }

    /**
     * The confirm warranted by cycling recapture from {@code from} to {@code to}: {@link #RECAPTURE} when the new mode
     * is strictly less fresh (fewer of the two capabilities, so already-downloaded or nearby areas go staler), else
     * {@link #NONE}. Turning recapture up loses no data and takes no confirm.
     */
    public static CaptureToggleGuard whenReducingRecapture(RecaptureMode from, RecaptureMode to) {
        return freshnessRank(to) < freshnessRank(from) ? RECAPTURE : NONE;
    }

    private static int freshnessRank(RecaptureMode mode) {
        return (mode.refreshesHotChunks() ? 1 : 0) + (mode.overwritesRevisitedChunks() ? 1 : 0);
    }

    /**
     * Whether either of the two core capture kinds (entities, containers) is off, so the download will be missing that
     * data. Drives the passive "capture partially disabled" indicator on the settings screen and at the download
     * action; recapture is excluded, since turning it off loses no data (see {@link #RECAPTURE}). Chunk capture is a
     * no-knob invariant, so it is never a factor here.
     */
    public static boolean isCapturePartiallyDisabled(WdlConfig config) {
        return !config.captureEntities() || !config.captureContainers();
    }
}
