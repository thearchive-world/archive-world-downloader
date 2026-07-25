// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The closed value-type classifier: each type's parse (with clamp/malformed/blank rules) and canonical format. */
class ConfigTypeTest {
    private static final Function<WdlConfig, Object> accessor = config -> Boolean.TRUE;

    private static List<String> flags() {
        return new ArrayList<>();
    }

    @Test
    void booleanParsesTrimmedCaseInsensitivelyAndFlagsGarbage() {
        ConfigOption option = ConfigOption.booleanOption("flag", "true", accessor, "");
        List<String> malformed = flags();

        assertEquals(Boolean.TRUE, option.type.parse(option, " TRUE ", malformed));
        assertEquals(Boolean.FALSE, option.type.parse(option, "false", malformed));
        assertTrue(malformed.isEmpty());

        assertNull(option.type.parse(option, "ture", malformed));
        assertTrue(malformed.contains("flag"));
        assertEquals("true", option.type.format(Boolean.TRUE));
    }

    @Test
    void unboundedIntegerParsesWithoutClampingAndFlagsGarbage() {
        ConfigOption option = ConfigOption.rangedInteger("count", "15", Integer.MIN_VALUE, Integer.MAX_VALUE, accessor,
                "");
        List<String> malformed = flags();

        assertEquals(15, option.type.parse(option, " 15 ", malformed));
        assertEquals(999999, option.type.parse(option, "999999", malformed), "an unbounded int does not clamp");
        assertTrue(malformed.isEmpty());

        assertNull(option.type.parse(option, "lots", malformed));
        assertTrue(malformed.contains("count"));
        assertEquals("42", option.type.format(42));
    }

    @Test
    void rangedIntegerClampsWithoutFlaggingAndFlagsOnlyGarbage() {
        ConfigOption option = ConfigOption.rangedInteger("opacity", "56", 0, 100, accessor, "");
        List<String> malformed = flags();

        assertEquals(100, option.type.parse(option, "250", malformed), "clamps to max");
        assertEquals(0, option.type.parse(option, "-4", malformed), "clamps to min");
        assertTrue(malformed.isEmpty(), "clamping is not a malformed edit");

        assertNull(option.type.parse(option, "lots", malformed));
        assertTrue(malformed.contains("opacity"));
    }

    @Test
    void longParsesFullRangeHashesTextAndTakesDefaultWhenBlank() {
        ConfigOption option = ConfigOption.longSeed("seed", "0", accessor, "");
        List<String> malformed = flags();

        assertEquals(Long.MIN_VALUE, option.type.parse(option, Long.toString(Long.MIN_VALUE), malformed));
        assertEquals((long) "hello".hashCode(), option.type.parse(option, "hello", malformed),
                "non-numeric text hashes like the vanilla seed field");
        assertNull(option.type.parse(option, "   ", malformed), "a blank seed falls back to the default");
        assertTrue(malformed.isEmpty(), "any seed text is valid, so nothing is flagged");
        assertEquals("12345", option.type.format(12345L));
    }

    @Test
    void floatClampsFiniteAndFlagsNonFiniteOrGarbage() {
        ConfigOption option = ConfigOption.rangedFloat("scale", "1.0", 0.5f, 4.0f, accessor, "");
        List<String> malformed = flags();

        assertEquals(2.0f, option.type.parse(option, "2.0", malformed));
        assertEquals(4.0f, option.type.parse(option, "99", malformed), "clamps to max");
        assertEquals(0.5f, option.type.parse(option, "-1", malformed), "clamps to min");
        assertTrue(malformed.isEmpty());

        for (String bad : new String[] { "abc", "NaN", "Infinity", "-Infinity" }) {
            List<String> flagged = flags();
            assertNull(option.type.parse(option, bad, flagged), bad);
            assertTrue(flagged.contains("scale"), bad);
        }
        assertEquals("1.0", option.type.format(1.0f));
    }

    @Test
    void enumParsesUppercasedAndFlagsUnknownConstant() {
        ConfigOption option = ConfigOption.enumOption("anchor", "TOP_CENTER",
                name -> HudAnchor.valueOf(name), accessor, "");
        List<String> malformed = flags();

        assertEquals(HudAnchor.BOTTOM_RIGHT, option.type.parse(option, "bottom_right", malformed),
                "the raw value is uppercased before lookup");
        assertTrue(malformed.isEmpty());

        assertNull(option.type.parse(option, "DIAGONAL", malformed));
        assertTrue(malformed.contains("anchor"));
        assertEquals("TOP_CENTER", option.type.format(HudAnchor.TOP_CENTER));
    }
}
