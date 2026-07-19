// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SizeFormatterTest {
    @Test
    void formatsBytesBelowOneKibibyte() {
        assertSize(0, "0", "wdl.size.b");
        assertSize(512, "512", "wdl.size.b");
        assertSize(1023, "1023", "wdl.size.b");
    }

    @Test
    void formatsEachBinaryMagnitude() {
        assertSize(1024L, "1", "wdl.size.kib");
        assertSize(1024L * 1024, "1", "wdl.size.mib");
        assertSize(1024L * 1024 * 1024, "1", "wdl.size.gib");
    }

    @Test
    void trimsTrailingZerosAndKeepsSignificantDecimals() {
        assertSize(5 * 1024, "5", "wdl.size.kib");
        assertSize(1024L * 1024 + 512 * 1024, "1.5", "wdl.size.mib");
        assertSize(1024L * 1024 * 1024 * 5 / 2, "2.5", "wdl.size.gib");
    }

    @Test
    void keepsScalingInGibibytesWithNoTerabyteUnit() {
        assertSize(1024L * 1024 * 1024 * 1024, "1024", "wdl.size.gib");
    }

    @Test
    void rollsOverWhenRoundingWouldReachTheNextUnit() {
        assertSize(1024L * 1024 - 1, "1", "wdl.size.mib");
        assertSize(1024L * 1024 * 1024 - 1, "1", "wdl.size.gib");
    }

    private static void assertSize(long bytes, String number, String unitKey) {
        SizeFormatter.Size size = SizeFormatter.format(bytes);
        assertEquals(number, size.number());
        assertEquals(unitKey, size.unitKey());
    }
}
