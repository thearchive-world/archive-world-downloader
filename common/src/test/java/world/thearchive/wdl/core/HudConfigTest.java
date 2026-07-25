// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The HUD config block: defaults, enum and integer parsing, range clamping, and malformed self-heal flags. */
class HudConfigTest {
    @Test
    void showHudDefaultsOnAndParsesOff() {
        assertTrue(WdlConfig.DEFAULTS.hud().showHud(), "the HUD master defaults on");

        Properties properties = new Properties();
        properties.setProperty("showHud", "false");
        assertFalse(WdlConfig.parse(properties).hud().showHud(), "the master reads its stored value");
    }

    @Test
    void defaultsAreTopCenterHoldPeekPanelOff() {
        HudConfig hud = WdlConfig.DEFAULTS.hud();
        assertTrue(hud.showHud());
        assertEquals(HudAnchor.TOP_CENTER, hud.anchor());
        assertEquals(0, hud.offsetX());
        assertEquals(0, hud.offsetY());
        assertFalse(hud.detailed());
        assertEquals(HudPeekMode.HOLD, hud.peekMode());
        assertFalse(hud.background());
        assertEquals(56, hud.panelOpacity());
        assertEquals(8, hud.doneLingerSeconds());
    }

    @Test
    void parsesEachKey() {
        Properties properties = new Properties();
        properties.setProperty("hudAnchor", "BOTTOM_RIGHT");
        properties.setProperty("hudOffsetX", "20");
        properties.setProperty("hudOffsetY", "-15");
        properties.setProperty("hudDetailed", "true");
        properties.setProperty("hudPeekMode", "TOGGLE");
        properties.setProperty("hudBackground", "true");
        properties.setProperty("hudPanelOpacity", "80");
        properties.setProperty("hudDoneLingerSeconds", "12");

        HudConfig hud = WdlConfig.parse(properties).hud();
        assertEquals(HudAnchor.BOTTOM_RIGHT, hud.anchor());
        assertEquals(20, hud.offsetX());
        assertEquals(-15, hud.offsetY());
        assertTrue(hud.detailed());
        assertEquals(HudPeekMode.TOGGLE, hud.peekMode());
        assertTrue(hud.background());
        assertEquals(80, hud.panelOpacity());
        assertEquals(12, hud.doneLingerSeconds());
    }

    @Test
    void outOfRangeIntegersClampRatherThanFail() {
        Properties properties = new Properties();
        properties.setProperty("hudPanelOpacity", "250");
        properties.setProperty("hudDoneLingerSeconds", "-4");
        properties.setProperty("hudOffsetX", "9999");

        HudConfig hud = WdlConfig.parse(properties).hud();
        assertEquals(100, hud.panelOpacity(), "opacity clamps to 100");
        assertEquals(0, hud.doneLingerSeconds(), "linger clamps to 0");
        assertEquals(200, hud.offsetX(), "offset clamps to the screen-offset bound");
    }

    @Test
    void malformedValuesAreFlaggedForSelfHealAndFallBackToDefault() {
        Properties properties = new Properties();
        properties.setProperty("hudAnchor", "DIAGONAL");
        properties.setProperty("hudPeekMode", "WIGGLE");
        properties.setProperty("hudPanelOpacity", "lots");

        List<String> malformed = new ArrayList<>();
        HudConfig hud = WdlConfig.parse(properties, malformed).hud();
        assertEquals(HudAnchor.TOP_CENTER, hud.anchor());
        assertEquals(HudPeekMode.HOLD, hud.peekMode());
        assertEquals(56, hud.panelOpacity());
        assertTrue(malformed.contains("hudAnchor"));
        assertTrue(malformed.contains("hudPeekMode"));
        assertTrue(malformed.contains("hudPanelOpacity"));
    }

    @Test
    void aMissingKeyIsNotMalformed() {
        List<String> malformed = new ArrayList<>();
        WdlConfig.parse(new Properties(), malformed);
        assertTrue(malformed.isEmpty(), "an absent key keeps its default and is not a self-heal trigger");
    }
}
