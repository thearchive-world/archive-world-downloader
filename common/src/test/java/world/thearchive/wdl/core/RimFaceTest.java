// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The exposed-face selection rule: given the six neighbor-sealed booleans, the rim sits on the first open face in the
 * preference order top, bottom, then the sides; a fully-enclosed container draws nothing.
 */
class RimFaceTest {
    @Test
    void nothingSealedPrefersTop() {
        assertEquals(RimFace.TOP, RimFace.selectExposed(false, false, false, false, false, false));
    }

    @Test
    void topSealedFallsToBottom() {
        assertEquals(RimFace.BOTTOM, RimFace.selectExposed(true, false, false, false, false, false));
    }

    @Test
    void topAndBottomSealedFallToTheFirstOpenSide() {
        assertEquals(RimFace.NORTH, RimFace.selectExposed(true, true, false, false, false, false));
        assertEquals(RimFace.SOUTH, RimFace.selectExposed(true, true, true, false, false, false));
        assertEquals(RimFace.WEST, RimFace.selectExposed(true, true, true, true, false, false));
        assertEquals(RimFace.EAST, RimFace.selectExposed(true, true, true, true, true, false));
    }

    @Test
    void fullyEnclosedDrawsNothing() {
        assertEquals(RimFace.NONE, RimFace.selectExposed(true, true, true, true, true, true));
    }

    @Test
    void topWinsOverAnOpenSide() {
        assertEquals(RimFace.TOP, RimFace.selectExposed(false, true, false, true, false, true));
    }
}
