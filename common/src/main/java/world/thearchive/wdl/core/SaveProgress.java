// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

public final class SaveProgress {
    private volatile SaveStage stage = SaveStage.NONE;
    private volatile long done;
    private volatile long total;

    public void chunks(long drained, long submitted) {
        publish(SaveStage.WRITING_CHUNKS, drained, submitted);
    }

    public void maps(long written, long mapCount) {
        publish(SaveStage.WRITING_MAPS, written, mapCount);
    }

    public void compressing(long bytesZipped, long byteTotal) {
        publish(SaveStage.COMPRESSING, bytesZipped, byteTotal);
    }

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

    // Write done and total before the stage: a reader that observes the new stage then sees no earlier phase's counts.
    private void publish(SaveStage phase, long phaseDone, long phaseTotal) {
        this.done = phaseDone;
        this.total = phaseTotal;
        this.stage = phase;
    }
}
