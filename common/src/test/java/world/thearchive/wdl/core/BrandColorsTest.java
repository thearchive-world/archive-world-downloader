// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the one home-of-record color identity by exact hex, so a drifted token is caught here rather than on a surface.
 * RGB-only: consumers force {@code 0xFF} alpha at the draw site.
 */
class BrandColorsTest {
    @Test
    void brandColorsMatchTheirHex() {
        assertEquals(0xFFB84C, BrandColors.AMBER);
        assertEquals(0xFFD680, BrandColors.AMBER_HOVER);
        assertEquals(0xF0EAD6, BrandColors.IVORY);
        assertEquals(0xAAAAAA, BrandColors.GRAY);
        assertEquals(0xD04040, BrandColors.RUST);
        assertEquals(0x5BC0BE, BrandColors.TEAL);
        assertEquals(0x92D5D4, BrandColors.TEAL_HOVER);
        assertEquals(0xE53935, BrandColors.RECORDING_RED);
        assertEquals(0xC8CDD2, BrandColors.SAVING_GRAY);
        assertEquals(0x14161C, BrandColors.PANEL);
    }

    @Test
    void brandColorsAreRgbOnly() {
        // No alpha byte baked into the token: every value sits within 0xFFFFFF.
        assertEquals(0, BrandColors.AMBER & ~0xFFFFFF);
        assertEquals(0, BrandColors.IVORY & ~0xFFFFFF);
        assertEquals(0, BrandColors.TEAL & ~0xFFFFFF);
        assertEquals(0, BrandColors.RECORDING_RED & ~0xFFFFFF);
        assertEquals(0, BrandColors.SAVING_GRAY & ~0xFFFFFF);
        assertEquals(0, BrandColors.PANEL & ~0xFFFFFF);
    }
}
