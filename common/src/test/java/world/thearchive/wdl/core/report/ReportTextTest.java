// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Hostile server text: strip section codes, flatten to one line, neutralize markdown; mod text untouched. */
class ReportTextTest {
    private static final char SECTION = '§';

    @Test
    void stripsSectionSignFollowedByValidFormatCode() {
        assertEquals("red text", ReportText.stripFormatCodes(SECTION + "cred text"));
        assertEquals("bold", ReportText.stripFormatCodes(SECTION + "lbold"));
        assertEquals("reset", ReportText.stripFormatCodes(SECTION + "rreset"));
    }

    @Test
    void stripsTheCodeCaseInsensitively() {
        assertEquals("X", ReportText.stripFormatCodes(SECTION + "CX")); // the game renders §C like §c
    }

    @Test
    void keepsLoneSectionSignNotFollowedByValidCode() {
        assertEquals("100" + SECTION + " off", ReportText.stripFormatCodes("100" + SECTION + " off"));
        assertEquals("ends" + SECTION, ReportText.stripFormatCodes("ends" + SECTION));
        assertEquals(SECTION + "z", ReportText.stripFormatCodes(SECTION + "z")); // z is not a format code
    }

    @Test
    void flattensNewlinesAndControlCharsToSingleLine() {
        assertEquals("line one line two end", ReportText.oneLine("line one\nline two\r\tend"));
    }

    @Test
    void escapesTheInlineDangerousMetacharacters() {
        // Links/images (via the brackets), emphasis, inline code, autolinks/HTML, and strikethrough
        assertEquals("\\[link\\](http://x)", ReportText.escapeMarkdown("[link](http://x)"));
        assertEquals("\\*b\\* \\_i\\_ \\`c\\` \\<x> \\~s\\~", ReportText.escapeMarkdown("*b* _i_ `c` <x> ~s~"));
        assertEquals("a\\\\b", ReportText.escapeMarkdown("a\\b"));
    }

    @Test
    void leavesStartOfLineOnlyMetacharactersCleanMidLine() {
        // A hash, hyphen, period, plus, or angle-close is only special at the start of a line, and the value
        // is always rendered mid-line after a label, so escaping them would only add noise
        assertEquals("v26.1.2 - rc#3 (final) > go", ReportText.escapeMarkdown("v26.1.2 - rc#3 (final) > go"));
    }

    @Test
    void escapeServerTextStripsFlattensAndEscapesTogether() {
        String hostile = SECTION + "c[click](http://evil)\n# Heading\n- item";

        String safe = ReportText.escapeServerText(hostile);

        assertFalse(safe.contains(SECTION + "c"), "section code is gone");
        assertFalse(safe.contains("\n"), "flattened to one line");
        assertEquals("\\[click\\](http://evil) # Heading - item", safe);
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("survival world at spawn", ReportText.escapeServerText("survival world at spawn"));
    }
}
