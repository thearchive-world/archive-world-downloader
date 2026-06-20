// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The MC-free status formatting helpers: the two elapsed shapes and the shortened count. */
class CaptureStatusTest {
    @Test
    void elapsedFormatsAsMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", CaptureStatus.elapsed(0));
        assertEquals("0:05", CaptureStatus.elapsed(5_000));
        assertEquals("0:59", CaptureStatus.elapsed(59_999), "rounds down to the whole second");
        assertEquals("1:05", CaptureStatus.elapsed(65_000));
        assertEquals("61:01", CaptureStatus.elapsed(3_661_000), "minutes are not capped at an hour");
    }

    @Test
    void elapsedClampsNegativeToZero() {
        assertEquals("0:00", CaptureStatus.elapsed(-1));
    }

    @Test
    void completionElapsedIsMinutesUnderAnHourThenHoursMinutesSeconds() {
        assertEquals("0:07", CaptureStatus.completionElapsed(7_000));
        assertEquals("4:07", CaptureStatus.completionElapsed(247_000));
        assertEquals("59:59", CaptureStatus.completionElapsed(3_599_000));
        assertEquals("1:00:00", CaptureStatus.completionElapsed(3_600_000));
        assertEquals("1:02:33", CaptureStatus.completionElapsed(3_753_000));
    }

    @Test
    void completionElapsedClampsNegativeToZero() {
        assertEquals("0:00", CaptureStatus.completionElapsed(-1));
    }

    @Test
    void compactCountIsExactBelowTenThousandThenShortened() {
        assertEquals("0", CaptureStatus.compactCount(0));
        assertEquals("9999", CaptureStatus.compactCount(9_999), "exact and separator-free up to the ten-thousand line");
        assertEquals("10.0k", CaptureStatus.compactCount(10_000), "k carries one decimal, like M and B");
        assertEquals("12.9k", CaptureStatus.compactCount(12_999), "the decimal truncates down");
        assertEquals("100k", CaptureStatus.compactCount(100_000), "the decimal drops once the whole reaches 100");
        assertEquals("999k", CaptureStatus.compactCount(999_999));
        assertEquals("1.0M", CaptureStatus.compactCount(1_000_000));
        assertEquals("12.3M", CaptureStatus.compactCount(12_345_678));
        assertEquals("99.9M", CaptureStatus.compactCount(99_999_999));
        assertEquals("100M", CaptureStatus.compactCount(100_000_000), "the decimal drops once the whole reaches 100");
        assertEquals("999M", CaptureStatus.compactCount(999_999_999));
        assertEquals("1.0B", CaptureStatus.compactCount(1_000_000_000));
        assertEquals("2.1B", CaptureStatus.compactCount(2_147_483_647L), "int-max chunks, the realistic ceiling");
        assertEquals("12.9B", CaptureStatus.compactCount(12_999_999_999L), "the B remainder uses long arithmetic");
        assertEquals("100B", CaptureStatus.compactCount(100_000_000_000L));
    }
}
