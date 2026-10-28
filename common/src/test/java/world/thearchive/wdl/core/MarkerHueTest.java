// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkerHueTest {
    @Test
    void markerHuesExposeTheirRgb() {
        assertEquals(0xDE0000, MarkerHue.RED.rgb());
        assertEquals(0x8A5CFF, MarkerHue.VIOLET.rgb());
        assertEquals(0x5BC0BE, MarkerHue.TEAL.rgb());
        assertEquals(0xFFB84C, MarkerHue.AMBER.rgb());
        assertEquals(0xF0E442, MarkerHue.YELLOW.rgb());
        assertEquals(0x0072B2, MarkerHue.BLUE.rgb());
        assertEquals(0xCC79A7, MarkerHue.REDDISH_PURPLE.rgb());
        assertEquals(0xFFFFFF, MarkerHue.WHITE.rgb());
    }

    @Test
    void theTwoOverlayDefaultsCarryTheirBrandValue() {
        assertEquals(BrandColors.TEAL, MarkerHue.TEAL.rgb());
        assertEquals(BrandColors.AMBER, MarkerHue.AMBER.rgb());
    }

    @Test
    void markerHuesAreRgbOnly() {
        for (MarkerHue hue : MarkerHue.values()) {
            assertEquals(0, hue.rgb() & ~0xFFFFFF, hue + " must store RGB only (no alpha byte)");
        }
    }

    @Test
    void theCuratedSetIsExactlyEight() {
        assertEquals(8, MarkerHue.values().length);
    }

    @Test
    void presetCycleIsTheBrandDefaultThenTheAlternativesThenTheOtherSurface() {
        assertEquals(Arrays.asList(MarkerHue.RED, MarkerHue.BLUE, MarkerHue.REDDISH_PURPLE,
                MarkerHue.WHITE, MarkerHue.TEAL, MarkerHue.AMBER), MarkerHue.presetCycle(MarkerHue.RED));
        assertEquals(Arrays.asList(MarkerHue.VIOLET, MarkerHue.BLUE, MarkerHue.REDDISH_PURPLE,
                MarkerHue.WHITE, MarkerHue.TEAL, MarkerHue.AMBER), MarkerHue.presetCycle(MarkerHue.VIOLET));
        assertEquals(Arrays.asList(MarkerHue.TEAL, MarkerHue.YELLOW, MarkerHue.BLUE, MarkerHue.REDDISH_PURPLE,
                MarkerHue.WHITE, MarkerHue.RED, MarkerHue.VIOLET), MarkerHue.presetCycle(MarkerHue.TEAL));
        assertEquals(Arrays.asList(MarkerHue.AMBER, MarkerHue.BLUE, MarkerHue.REDDISH_PURPLE,
                MarkerHue.WHITE, MarkerHue.RED, MarkerHue.VIOLET), MarkerHue.presetCycle(MarkerHue.AMBER));
    }

    @Test
    void noCycleOffersBothAmberAndYellow() {
        for (MarkerHue brandDefault : Arrays.asList(MarkerHue.RED, MarkerHue.VIOLET, MarkerHue.TEAL,
                MarkerHue.AMBER)) {
            List<MarkerHue> cycle = MarkerHue.presetCycle(brandDefault);
            assertFalse(cycle.contains(MarkerHue.AMBER) && cycle.contains(MarkerHue.YELLOW),
                    brandDefault + " must not offer AMBER and YELLOW together");
        }
        assertTrue(MarkerHue.presetCycle(MarkerHue.TEAL).contains(MarkerHue.YELLOW),
                "the covered row never reaches AMBER, so it keeps YELLOW");
    }

    @Test
    void presetCycleNeverSurfacesTheSiblingRolesBrandHue() {
        List<MarkerHue> unscanned = MarkerHue.presetCycle(MarkerHue.RED);
        assertFalse(unscanned.contains(MarkerHue.VIOLET), "the unscanned cycle omits the recovered brand hue");
        assertTrue(unscanned.contains(MarkerHue.TEAL), "the outline surface may borrow an overlay brand hue");

        List<MarkerHue> covered = MarkerHue.presetCycle(MarkerHue.TEAL);
        assertFalse(covered.contains(MarkerHue.AMBER), "the covered cycle omits the suspect brand hue");
        assertTrue(covered.contains(MarkerHue.RED), "the overlay surface may borrow an outline brand hue");
    }

    @Test
    void everyCycleLeadsWithItsDefaultAndRepeatsNoHue() {
        for (MarkerHue brandDefault : Arrays.asList(MarkerHue.RED, MarkerHue.VIOLET, MarkerHue.TEAL,
                MarkerHue.AMBER)) {
            List<MarkerHue> cycle = MarkerHue.presetCycle(brandDefault);
            assertEquals(cycle.size(), new LinkedHashSet<>(cycle).size(), brandDefault + " cycles no hue twice");
            assertEquals(brandDefault, cycle.get(0), brandDefault + " leads its own cycle");
            assertTrue(cycle.size() >= 6, brandDefault + " keeps a usable set of alternatives");
        }
    }
}
