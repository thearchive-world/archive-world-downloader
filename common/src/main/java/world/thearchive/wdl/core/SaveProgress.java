// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The live finalization progress the background save reports for the HUD bar. MC-free and Java-8-clean, so it sits
 * behind the count seam. One phase is current at a time, each with a {@code done} out of {@code total}; the bar resets
 * to zero whenever a phase begins because the caller passes {@code done = 0} at the boundary.
 *
 * <p>Threading: the background writer thread sets the phase and counts while the client main thread reads
 * {@link #stage()} / {@link #fraction()} each frame, so the fields are {@code volatile}. The numbers are a display
 * value, so a one-frame skew at a boundary is harmless and no lock is taken.
 */
public final class SaveProgress {
    private volatile SaveStage stage = SaveStage.NONE;
    private volatile long done;
    private volatile long total;

    /** Enter or advance the chunk-drain phase: {@code drained} of {@code submitted} write tasks done. */
    public void chunks(long drained, long submitted) {
        publish(SaveStage.WRITING_CHUNKS, drained, submitted);
    }

    /** Enter or advance the map-write phase: {@code written} of {@code mapCount} captured maps flushed. */
    public void maps(long written, long mapCount) {
        publish(SaveStage.WRITING_MAPS, written, mapCount);
    }

    /** Enter or advance the export-zip phase: {@code bytesZipped} of the folder's {@code byteTotal} compressed. */
    public void compressing(long bytesZipped, long byteTotal) {
        publish(SaveStage.COMPRESSING, bytesZipped, byteTotal);
    }

    /** Return to idle; the HUD draws no bar. */
    public void idle() {
        publish(SaveStage.NONE, 0, 0);
    }

    public SaveStage stage() {
        return stage;
    }

    /** The current phase's fraction in {@code [0, 1]}; zero when idle or its total is not yet known. */
    public float fraction() {
        long phaseTotal = total;
        if (phaseTotal <= 0) {
            return 0.0f;
        }
        long phaseDone = done;
        if (phaseDone >= phaseTotal) {
            return 1.0f;
        }
        return (float) phaseDone / (float) phaseTotal;
    }

    // Write done and total before the stage: a reader that observes the new stage then sees this phase's counts,
    // not the prior phase's, so the bar never shows a stale fraction under the relabeled phase.
    private void publish(SaveStage phase, long phaseDone, long phaseTotal) {
        this.done = phaseDone;
        this.total = phaseTotal;
        this.stage = phase;
    }
}
