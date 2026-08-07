// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The capture-semantics toggles as one value, so what a running download latched at start can be compared knob by knob
 * against what the settings currently say. A download reads its config once and keeps capturing under it; the settings
 * menu keeps publishing a live config, and the two diverge the moment a capture toggle is edited mid-download.
 *
 * <p>Only knobs a running download reads for itself are carried. A knob nothing latches has no second value to disagree
 * with, so the hues, the outline distance and the coverage-overlay master stay live and are absent here.
 */
public final class CaptureToggles {
    private final boolean renderUnsavedOutline;
    private final boolean captureContainers;
    private final boolean captureEntities;
    private final boolean refreshesHotChunks;
    private final boolean savePlayerEnderChest;

    CaptureToggles(boolean renderUnsavedOutline, boolean captureContainers, boolean captureEntities,
            boolean refreshesHotChunks, boolean savePlayerEnderChest) {
        this.renderUnsavedOutline = renderUnsavedOutline;
        this.captureContainers = captureContainers;
        this.captureEntities = captureEntities;
        this.refreshesHotChunks = refreshesHotChunks;
        this.savePlayerEnderChest = savePlayerEnderChest;
    }

    /** The capture-semantics toggles of {@code config}, taken by value so the snapshot outlives the reference. */
    public static CaptureToggles from(WdlConfig config) {
        return new CaptureToggles(config.outline().renderUnsavedOutline(), config.captureContainers(),
                config.captureEntities(), config.recaptureChunks().refreshesHotChunks(),
                config.savePlayerEnderChest());
    }

    /**
     * The toggles a running download latches, which differ from {@link #from} on the outline master alone: the only
     * capture work that reads it is the recovered-coverage scan a resume runs, so a download that is not a resume acts
     * on no value of it and reports it on. Latching an opinion the download never acts on would leave an aid gated on
     * it, and switching the outline on mid-download would then draw nothing at all.
     */
    public static CaptureToggles latchedBy(WdlConfig config, boolean resumeDownload) {
        return new CaptureToggles(!resumeDownload || config.outline().renderUnsavedOutline(),
                config.captureContainers(), config.captureEntities(),
                config.recaptureChunks().refreshesHotChunks(), config.savePlayerEnderChest());
    }

    CaptureToggles and(CaptureToggles other) {
        return new CaptureToggles(renderUnsavedOutline && other.renderUnsavedOutline,
                captureContainers && other.captureContainers, captureEntities && other.captureEntities,
                refreshesHotChunks && other.refreshesHotChunks,
                savePlayerEnderChest && other.savePlayerEnderChest);
    }

    public boolean renderUnsavedOutline() {
        return renderUnsavedOutline;
    }

    public boolean captureContainers() {
        return captureContainers;
    }

    public boolean captureEntities() {
        return captureEntities;
    }

    /** Whether the recapture mode refreshes already-saved hot chunks, which is what carries an interaction write. */
    public boolean refreshesHotChunks() {
        return refreshesHotChunks;
    }

    /** Whether the player's ender-chest contents reach the save, rather than being stripped at finish. */
    public boolean savePlayerEnderChest() {
        return savePlayerEnderChest;
    }
}
