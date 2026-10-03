// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

final class ReportText {
    private static final char SECTION_SIGN = '§';

    private static final String FORMAT_CODES = "0123456789abcdefklmnor";

    // The metacharacters that are dangerous inline: link/image brackets, emphasis, inline code, autolink/HTML open, and
    // strikethrough.
    private static final String MARKDOWN_METACHARACTERS = "\\`*_[]<~";

    private ReportText() {}

    static String escapeServerText(String raw) {
        return escapeMarkdown(oneLine(stripFormatCodes(raw)));
    }

    static String stripFormatCodes(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char character = raw.charAt(i);
            if (character == SECTION_SIGN && i + 1 < raw.length()
                    && FORMAT_CODES.indexOf(Character.toLowerCase(raw.charAt(i + 1))) >= 0) {
                i += 2;
            } else {
                out.append(character);
                i++;
            }
        }
        return out.toString();
    }

    static String oneLine(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        boolean started = false;
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                pendingSpace = started; // collapse runs, and never lead with a space
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(character);
                started = true;
            }
        }
        return out.toString();
    }

    static String escapeMarkdown(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (MARKDOWN_METACHARACTERS.indexOf(character) >= 0) {
                out.append('\\');
            }
            out.append(character);
        }
        return out.toString();
    }
}
