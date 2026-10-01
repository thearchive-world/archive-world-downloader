// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.browse;

import org.jspecify.annotations.Nullable;

final class WorldIcon {
    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    static final int MAX_BYTES = 512 * 1024;

    static final int ICON_DIMENSION = 64;

    private WorldIcon() {}

    // Signature (8) + IHDR length (4) + "IHDR" (4) + width (4) + height (4): the bytes this inspects.
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int IHDR_WIDTH_OFFSET = 16;
    private static final int IHDR_HEIGHT_OFFSET = 20;
    private static final int HEADER_BYTES = 24;

    static byte @Nullable [] validate(byte @Nullable [] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES || !hasValidPngHeader(bytes)) {
            return null;
        }
        return bytes;
    }

    private static boolean hasValidPngHeader(byte[] bytes) {
        if (bytes.length < HEADER_BYTES || !startsWithPngSignature(bytes) || !isIhdr(bytes)) {
            return false;
        }
        return readInt(bytes, IHDR_WIDTH_OFFSET) == ICON_DIMENSION
                && readInt(bytes, IHDR_HEIGHT_OFFSET) == ICON_DIMENSION;
    }

    private static boolean startsWithPngSignature(byte[] bytes) {
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIhdr(byte[] bytes) {
        return bytes[IHDR_TYPE_OFFSET] == 'I' && bytes[IHDR_TYPE_OFFSET + 1] == 'H'
                && bytes[IHDR_TYPE_OFFSET + 2] == 'D' && bytes[IHDR_TYPE_OFFSET + 3] == 'R';
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
    }
}
