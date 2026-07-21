// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The minimal flat-JSON writer and parser behind the durable machine record. */
class JsonTest {
    @Test
    void writesCompactObjectInInsertionOrder() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", 1L);
        fields.put("event", "start");
        fields.put("ok", true);

        assertEquals("{\"v\":1,\"event\":\"start\",\"ok\":true}", Json.writeObject(fields));
    }

    @Test
    void roundTripsStringsNumbersBooleansAndNull() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", "abc-123");
        fields.put("chunks", 4096L);
        fields.put("clean", false);
        fields.put("motd", null);

        Map<String, Object> back = Json.parseObject(Json.writeObject(fields));

        assertEquals("abc-123", back.get("id"));
        assertEquals(4096L, back.get("chunks"));
        assertEquals(false, back.get("clean"));
        assertTrue(back.containsKey("motd"), "an explicit null value is preserved as a present key");
        assertNull(back.get("motd"));
    }

    @Test
    void escapesStringControlAndMetacharacters() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("text", "a\"b\\c\nd\te");

        String line = Json.writeObject(fields);

        assertEquals("{\"text\":\"a\\\"b\\\\c\\nd\\te\"}", line);
        assertEquals("a\"b\\c\nd\te", Json.parseObject(line).get("text"));
    }

    @Test
    void formatsNumbersIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); // grouping/decimal separators differ from ROOT
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("chunks", 1234567L);

            assertEquals("{\"chunks\":1234567}", Json.writeObject(fields));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void parseRejectsMalformedLine() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{not json"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"k\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("\"bare string\""));
    }

    @Test
    void parsesWhitespaceBetweenEveryToken() {
        Map<String, Object> back = Json.parseObject("  { \"a\" : 1 , \"b\" : \"x\" }  ");

        assertEquals(1L, back.get("a"));
        assertEquals("x", back.get("b"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":1} x"),
                "trailing garbage after the closing brace is rejected");
    }

    @Test
    void parsesAnEmptyObjectWithSurroundingWhitespace() {
        Map<String, Object> back = Json.parseObject("  {  }  ");

        assertTrue(back.isEmpty());
        assertInstanceOf(LinkedHashMap.class, back, "the documented insertion-ordered, caller-owned map shape");
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{ } x"),
                "trailing garbage after an empty object is rejected");
    }

    @Test
    void writesSpacesLiterallyAndEscapesRawControlCharacters() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("text", "a b\u0001");

        String line = Json.writeObject(fields);

        assertEquals("{\"text\":\"a b\\u0001\"}", line);
        assertEquals("a b\u0001", Json.parseObject(line).get("text"));
    }
}
