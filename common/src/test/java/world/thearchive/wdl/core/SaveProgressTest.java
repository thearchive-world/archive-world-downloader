// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The finalization progress holder, asserted headless: the three phases each report a done/total fraction that resets
 * to zero at the phase boundary and clamps to 1.0, mirroring the writer-thread phase machine.
 */
class SaveProgressTest {
    private static final float DELTA = 1.0e-6f;

    @Test
    void aFreshHolderIsIdleAtZero() {
        SaveProgress progress = new SaveProgress();
        assertEquals(SaveStage.NONE, progress.stage());
        assertEquals(0.0f, progress.fraction(), DELTA);
    }

    @Test
    void chunksReportTheDrainedOverSubmittedFraction() {
        SaveProgress progress = new SaveProgress();
        progress.chunks(3, 10);
        assertEquals(SaveStage.WRITING_CHUNKS, progress.stage());
        assertEquals(0.3f, progress.fraction(), DELTA);

        progress.chunks(10, 10);
        assertEquals(1.0f, progress.fraction(), DELTA);
    }

    @Test
    void fractionClampsToOneWhenDoneExceedsTotal() {
        SaveProgress progress = new SaveProgress();
        progress.chunks(12, 10);
        assertEquals(1.0f, progress.fraction(), DELTA);
    }

    @Test
    void aZeroTotalReadsAsZeroRatherThanDivideByZero() {
        SaveProgress progress = new SaveProgress();
        progress.chunks(0, 0);
        assertEquals(SaveStage.WRITING_CHUNKS, progress.stage());
        assertEquals(0.0f, progress.fraction(), DELTA);
    }

    @Test
    void eachPhaseBoundaryResetsTheBarToZeroAndRelabels() {
        SaveProgress progress = new SaveProgress();
        progress.chunks(10, 10);
        assertEquals(1.0f, progress.fraction(), DELTA);

        progress.maps(0, 5);
        assertEquals(SaveStage.WRITING_MAPS, progress.stage(), "relabels to maps");
        assertEquals(0.0f, progress.fraction(), DELTA, "the bar resets to zero at the boundary");

        progress.maps(5, 5);
        assertEquals(1.0f, progress.fraction(), DELTA);

        progress.compressing(0, 2048);
        assertEquals(SaveStage.COMPRESSING, progress.stage(), "relabels to compressing");
        assertEquals(0.0f, progress.fraction(), DELTA, "the bar resets to zero at the boundary");

        progress.compressing(512, 2048);
        assertEquals(0.25f, progress.fraction(), DELTA);
    }

    @Test
    void idleClearsBackToNone() {
        SaveProgress progress = new SaveProgress();
        progress.compressing(512, 2048);
        progress.idle();
        assertEquals(SaveStage.NONE, progress.stage());
        assertEquals(0.0f, progress.fraction(), DELTA);
    }
}
