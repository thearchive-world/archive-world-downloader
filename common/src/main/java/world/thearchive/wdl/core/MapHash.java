// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The content identity of a filled map: a wide SHA-256 over the 16384 color bytes, the scale, and the dimension key.
 * {@code locked}, {@code xCenter} and {@code zCenter} are deliberately excluded: lock state follows the
 * {@code lockDownloadedMaps} knob rather than identity, and WDL hardcodes the centers, so two captures of the same
 * picture hash equal across sessions and servers. That stable identity is what lets {@link MapManifest} keep a map's
 * archive id fixed against a server that renumbers session-local ids.
 *
 * <p>Pure and MC-free: the colors, scale and dimension are read band-agnostically off the serialized inner
 * {@code "data"} tag by the adapter and passed here as plain values, so hashing adds no per-band coupling.
 */
public final class MapHash {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MapHash() {}

    /**
     * The lowercase 64-character SHA-256 hex of {@code colors} + {@code scale} + {@code dimension}. The three inputs
     * are framed at fixed offsets (the colors are fixed-width, the scale is four bytes, the dimension is terminal), so
     * no two distinct field tuples share a digest. SHA-256 is mandated on every JVM, so its absence is unreachable and
     * surfaces as an {@link IllegalStateException}.
     */
    public static String of(byte[] colors, int scale, String dimension) {
        MessageDigest digest = sha256();
        digest.update(colors);
        digest.update((byte) (scale >>> 24));
        digest.update((byte) (scale >>> 16));
        digest.update((byte) (scale >>> 8));
        digest.update((byte) scale);
        digest.update(dimension.getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
