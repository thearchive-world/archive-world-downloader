// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The single-source config descriptor: the schema-derived defaults and the report ordering. */
class ConfigSchemaTest {
    @Test
    void everyOptionRoundTripsFormatToParse() {
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            Object parsedDefault = option.type.parse(option, option.defaultValue, new ArrayList<>());
            assertNotNull(parsedDefault, option.key);
            String formatted = option.type.format(parsedDefault);
            Object reparsed = option.type.parse(option, formatted, new ArrayList<>());
            assertEquals(parsedDefault, reparsed, option.key + " must round-trip format then parse");
        }
    }

    @Test
    void reportOrderIsScalarNonNestedSubsetOfOptions() {
        Set<String> optionKeys = new LinkedHashSet<>();
        for (ConfigOption option : ConfigSchema.OPTIONS) {
            optionKeys.add(option.key);
        }
        for (ConfigOption option : ConfigSchema.REPORT_ORDER) {
            assertTrue(optionKeys.contains(option.key), option.key + " must be an option");
            assertFalse(option.key.startsWith("hud"), option.key + " is a HUD option and must not be reported");
            assertFalse(option.key.startsWith("outline"), option.key + " is an outline option");
        }
        Set<String> reportKeys = new LinkedHashSet<>();
        for (ConfigOption option : ConfigSchema.REPORT_ORDER) {
            reportKeys.add(option.key);
        }
        assertFalse(reportKeys.contains("overlayCoveredColor"), "the covered marker hue is not reported");
        assertFalse(reportKeys.contains("overlaySuspectColor"), "the suspect marker hue is not reported");
        assertFalse(reportKeys.contains("renderCoverageOverlay"), "the overlay toggle is not reported");
    }
}
