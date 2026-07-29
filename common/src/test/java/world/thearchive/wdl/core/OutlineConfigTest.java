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

/** The unsaved-container outline config block: defaults, parsing, range clamping, and malformed self-heal. */
class OutlineConfigTest {
    @Test
    void defaultsAreOnAt96RedAndViolet() {
        OutlineConfig outline = WdlConfig.DEFAULTS.outline();
        assertTrue(outline.renderUnsavedOutline());
        assertEquals(96, outline.outlineDistance());
        assertEquals(MarkerHue.RED, outline.unscannedColor());
        assertEquals(MarkerHue.VIOLET, outline.recoveredColor());
        assertEquals(1.0f, outline.lineWidthScale());
        assertFalse(outline.debugTiming());
    }

    @Test
    void parsesEachKey() {
        Properties properties = new Properties();
        properties.setProperty("renderUnsavedOutline", "false");
        properties.setProperty("outlineDistance", "128");
        properties.setProperty("unscannedColor", "YELLOW");
        properties.setProperty("recoveredColor", "BLUE");
        properties.setProperty("outlineLineWidthScale", "2.0");
        OutlineConfig outline = WdlConfig.parse(properties).outline();
        assertFalse(outline.renderUnsavedOutline());
        assertEquals(128, outline.outlineDistance());
        assertEquals(MarkerHue.YELLOW, outline.unscannedColor());
        assertEquals(MarkerHue.BLUE, outline.recoveredColor());
        assertEquals(2.0f, outline.lineWidthScale());
    }

    @Test
    void distanceClampsToRangeWithoutFlaggingMalformed() {
        Properties properties = new Properties();
        properties.setProperty("outlineDistance", "999");
        List<String> malformed = new ArrayList<>();
        assertEquals(256, WdlConfig.parse(properties, malformed).outline().outlineDistance());

        properties.setProperty("outlineDistance", "0");
        assertEquals(1, WdlConfig.parse(properties, malformed).outline().outlineDistance());

        assertTrue(malformed.isEmpty());
    }

    @Test
    void lineWidthScaleClampsToRangeWithoutFlaggingMalformed() {
        Properties properties = new Properties();
        List<String> malformed = new ArrayList<>();

        properties.setProperty("outlineLineWidthScale", "99");
        assertEquals(4.0f, WdlConfig.parse(properties, malformed).outline().lineWidthScale());

        properties.setProperty("outlineLineWidthScale", "-1");
        assertEquals(0.5f, WdlConfig.parse(properties, malformed).outline().lineWidthScale());

        assertTrue(malformed.isEmpty()); // clamping to a bound is not a malformed edit
    }

    @Test
    void aNonNumericLineWidthScaleFlagsMalformedAndFallsBackToDefault() {
        Properties properties = new Properties();
        properties.setProperty("outlineLineWidthScale", "abc");
        List<String> malformed = new ArrayList<>();
        OutlineConfig outline = WdlConfig.parse(properties, malformed).outline();
        assertEquals(1.0f, outline.lineWidthScale());
        assertTrue(malformed.contains("outlineLineWidthScale"));
    }

    @Test
    void aNonFiniteLineWidthScaleFlagsMalformedAndFallsBackToDefault() {
        for (String value : new String[] { "NaN", "Infinity", "-Infinity" }) {
            Properties properties = new Properties();
            properties.setProperty("outlineLineWidthScale", value);
            List<String> malformed = new ArrayList<>();
            OutlineConfig outline = WdlConfig.parse(properties, malformed).outline();
            assertEquals(1.0f, outline.lineWidthScale(), value);
            assertTrue(malformed.contains("outlineLineWidthScale"), value);
        }
    }

    @Test
    void aBadColorFlagsMalformedAndFallsBackToDefault() {
        Properties properties = new Properties();
        properties.setProperty("unscannedColor", "CHARTREUSE");
        List<String> malformed = new ArrayList<>();
        OutlineConfig outline = WdlConfig.parse(properties, malformed).outline();
        assertEquals(MarkerHue.RED, outline.unscannedColor());
        assertTrue(malformed.contains("unscannedColor"));
    }
}
