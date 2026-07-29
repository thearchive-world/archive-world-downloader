// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure distance clamp: a container is a candidate only within the camera-centered outline distance. */
class OutlineClampTest {
    @Test
    void includesContainerInsideTheClamp() {
        assertTrue(OutlineClamp.isWithin(0, 0, 0, 10, 0, 0, 96));
    }

    @Test
    void excludesContainerBeyondTheClamp() {
        assertFalse(OutlineClamp.isWithin(0, 0, 0, 100, 0, 0, 96));
    }

    @Test
    void theBoundaryIsInclusive() {
        assertTrue(OutlineClamp.isWithin(0, 0, 0, 96, 0, 0, 96));
    }

    @Test
    void measuresInThreeDimensions() {
        assertFalse(OutlineClamp.isWithin(0, 0, 0, 60, 60, 60, 96));
        assertTrue(OutlineClamp.isWithin(0, 0, 0, 50, 50, 50, 96));
    }
}
