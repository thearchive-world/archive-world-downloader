// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.report;

/**
 * Hardens server-controlled text for the human rendering. The server address and MOTD are server-controlled, so a
 * hostile server could otherwise smuggle markup (links, images, headings) or extra report lines into the document.
 * {@link #escapeServerText(String)} strips the game's own section-sign format codes, flattens the value to a single
 * line, and neutralizes Markdown metacharacters. Mod-generated copy is never passed through here, so it renders as
 * authored.
 */
final class ReportText {
    private static final char SECTION_SIGN = '§';

    /** The vanilla single-character format codes: colors 0-9 / a-f, styles k-o, and reset r (case-blind). */
    private static final String FORMAT_CODES = "0123456789abcdefklmnor";

    // The metacharacters that are dangerous inline: link/image brackets, emphasis, inline code, autolink/HTML
    // open, and strikethrough. Block constructs (#, -, +, >, ordered-list digit-dot) are special only at the
    // start of a line, and server text is always rendered mid-line after a label with newlines already
    // flattened, so escaping them would only add noise (for example a version "26.1.2") for no added safety.
    private static final String MARKDOWN_METACHARACTERS = "\\`*_[]<~";

    private ReportText() {}

    /** Strip section codes, flatten to one line, and escape Markdown, in that order. */
    static String escapeServerText(String raw) {
        return escapeMarkdown(oneLine(stripFormatCodes(raw)));
    }

    /**
     * Drop a section sign only when it is followed by a valid format code, matching how the game consumes the pair
     * while rendering; a lone section sign (at end, or before a non-code character) is left.
     */
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

    /** Replace every run of whitespace or control characters with a single space and trim the ends. */
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

    /** Backslash-escape every Markdown metacharacter so server text cannot inject markup. */
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
