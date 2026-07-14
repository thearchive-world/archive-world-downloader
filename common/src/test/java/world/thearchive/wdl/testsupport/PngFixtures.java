// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

/**
 * A minimal-PNG fixture for the browse tests: the 8-byte signature followed by an IHDR chunk carrying a given width and
 * height, the smallest byte sequence WorldIcon.validate accepts. Shared so the icon-validation and catalog tests build
 * the same bytes rather than each carrying a near-copy.
 */
public final class PngFixtures {
    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    private PngFixtures() {}

    /** A minimal PNG: the 8-byte signature, then an IHDR chunk carrying {@code width} and {@code height}. */
    public static byte[] png(int width, int height) {
        byte[] bytes = new byte[33];
        System.arraycopy(PNG_SIGNATURE, 0, bytes, 0, 8);
        writeInt(bytes, 8, 13); // IHDR data length
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        writeInt(bytes, 16, width);
        writeInt(bytes, 20, height);
        return bytes;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
