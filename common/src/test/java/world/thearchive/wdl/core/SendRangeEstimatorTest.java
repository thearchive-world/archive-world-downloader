// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SendRangeEstimatorTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final SendRangeEstimator estimator = new SendRangeEstimator();

    @Test
    void uncalibratedDimensionReadsZeroRadiusAndNotCalibrated() {
        assertFalse(estimator.isCalibrated(OVERWORLD));
        assertEquals(0, estimator.radiusChunks(OVERWORLD, 32));
    }

    @Test
    void observeIsTheRunningMax() {
        estimator.observe(OVERWORLD, 40);
        estimator.observe(OVERWORLD, 150);
        estimator.observe(OVERWORLD, 90);
        assertEquals(150 >> 4, estimator.radiusChunks(OVERWORLD, 32));
    }

    @Test
    void radiusFloorsToChunks() {
        estimator.observe(OVERWORLD, 31);
        assertEquals(1, estimator.radiusChunks(OVERWORLD, 32));
        estimator.observe(OVERWORLD, 32);
        assertEquals(2, estimator.radiusChunks(OVERWORLD, 32));
    }

    @Test
    void dimensionsPartition() {
        estimator.observe(OVERWORLD, 150);
        assertFalse(estimator.isCalibrated(NETHER));
        assertEquals(0, estimator.radiusChunks(NETHER, 32));
    }

    @Test
    void capClampsAtReadAndTheMaxResurfacesWhenTheCapRises() {
        estimator.observe(OVERWORLD, 150);
        assertEquals(9, estimator.radiusChunks(OVERWORLD, 32));
        assertEquals(4, estimator.radiusChunks(OVERWORLD, 4));
        assertEquals(9, estimator.radiusChunks(OVERWORLD, 32));
    }

    @Test
    void anySampleCalibrates() {
        estimator.observe(OVERWORLD, 3);
        assertTrue(estimator.isCalibrated(OVERWORLD));
        assertEquals(0, estimator.radiusChunks(OVERWORLD, 32));
    }

    @Test
    void clearDropsEveryDimension() {
        estimator.observe(OVERWORLD, 150);
        estimator.observe(NETHER, 90);
        estimator.clear();
        assertFalse(estimator.isCalibrated(OVERWORLD));
        assertFalse(estimator.isCalibrated(NETHER));
        assertEquals(0, estimator.radiusChunks(OVERWORLD, 32));
    }
}
