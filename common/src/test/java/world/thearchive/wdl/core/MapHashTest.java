// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MapHashTest {
    private static byte[] colors(int fill) {
        byte[] colors = new byte[16384];
        Arrays.fill(colors, (byte) fill);
        return colors;
    }

    @Test
    void sameContentProducesSameHash() {
        assertEquals(MapHash.of(colors(7), 0, "minecraft:overworld"),
                MapHash.of(colors(7), 0, "minecraft:overworld"));
    }

    @Test
    void differentColorsProduceDifferentHashes() {
        byte[] original = colors(0);
        byte[] tweaked = colors(0);
        tweaked[8191] = 1; // flip a single interior byte
        assertNotEquals(MapHash.of(original, 0, "minecraft:overworld"), MapHash.of(tweaked, 0, "minecraft:overworld"));
    }

    @Test
    void differentScaleProducesDifferentHash() {
        assertNotEquals(MapHash.of(colors(3), 0, "minecraft:overworld"),
                MapHash.of(colors(3), 1, "minecraft:overworld"));
    }

    @Test
    void everyScaleByteFeedsTheDigestNotJustTheLowOne() {
        // The scale is hashed as four big-endian bytes (>>>24, >>>16, >>>8, then the low byte). Vary each upper
        // byte in isolation: a dropped update or a flipped shift direction on any of them would leave the digest
        // unchanged, which a test that only varies the low byte (above) cannot catch.
        byte[] colors = colors(3);
        String base = MapHash.of(colors, 0, "minecraft:overworld");
        assertNotEquals(base, MapHash.of(colors, 1 << 24, "minecraft:overworld"), "byte 3 (>>>24) must be mixed in");
        assertNotEquals(base, MapHash.of(colors, 1 << 16, "minecraft:overworld"), "byte 2 (>>>16) must be mixed in");
        assertNotEquals(base, MapHash.of(colors, 1 << 8, "minecraft:overworld"), "byte 1 (>>>8) must be mixed in");
    }

    @Test
    void differentDimensionProducesDifferentHash() {
        assertNotEquals(MapHash.of(colors(3), 0, "minecraft:overworld"),
                MapHash.of(colors(3), 0, "minecraft:the_nether"));
    }

    @Test
    void hashIsLowercaseSha256Hex() {
        String hash = MapHash.of(colors(0), 0, "minecraft:overworld");
        assertEquals(64, hash.length(), "SHA-256 is 256 bits = 64 hex characters");
        assertTrue(hash.matches("[0-9a-f]{64}"), "lowercase hex only");
    }
}
