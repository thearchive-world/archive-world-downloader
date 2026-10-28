// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import net.minecraft.client.gui.FontRenderer;

final class ClientText {
    private static final String ELLIPSIS = "...";

    private ClientText() {}

    static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String trimmed = text;
        while (!trimmed.isEmpty() && font.getStringWidth(trimmed) + font.getStringWidth(ELLIPSIS) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ELLIPSIS;
    }
}
