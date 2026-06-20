// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * MC-free status formatting helpers shared by the chat copy, the toast copy, and the rendered HUD overlay: the two
 * elapsed shapes and the shortened count, all headless-testable.
 */
public final class CaptureStatus {
    private CaptureStatus() {}

    /** The elapsed capture time as {@code m:ss}, minutes uncapped (a long download counts past an hour). */
    public static String elapsed(long millis) {
        ElapsedTime time = ElapsedTime.ofMillis(millis);
        return time.totalMinutes() + ":" + ElapsedTime.pad2(time.seconds());
    }

    /**
     * The elapsed time of a finished download: minutes and seconds under an hour, hours from one hour on, unlike the
     * uncapped-minutes HUD {@link #elapsed}.
     */
    static String completionElapsed(long millis) {
        ElapsedTime time = ElapsedTime.ofMillis(millis);
        if (time.hours() > 0) {
            return time.hours() + ":" + ElapsedTime.pad2(time.minutes()) + ":" + ElapsedTime.pad2(time.seconds());
        }
        return time.minutes() + ":" + ElapsedTime.pad2(time.seconds());
    }

    /**
     * A count shortened to at most five glyphs for the compact HUD, where a full grouped count would not fit: exact
     * below ten thousand, then k, M, and B, carrying one decimal until the whole reaches a hundred of that unit.
     * Separator-free below ten thousand (a locale-independent thousands comma reads as a decimal mark in the
     * comma-decimal locales, so a bare four-digit count is unambiguous where a grouped one is not); the only mark that
     * survives is the one-digit decimal point, which no thousands grouping can be mistaken for.
     */
    public static String compactCount(long count) {
        if (count < 10_000) {
            return Long.toString(count);
        }
        if (count < 1_000_000) {
            return scaled(count, 1_000) + "k";
        }
        if (count < 1_000_000_000) {
            return scaled(count, 1_000_000) + "M";
        }
        return scaled(count, 1_000_000_000) + "B";
    }

    /** One decimal place until the whole reaches a hundred of {@code unit}, then the whole alone, truncated down. */
    private static String scaled(long count, long unit) {
        long whole = count / unit;
        if (whole >= 100) {
            return Long.toString(whole);
        }
        long tenths = count % unit * 10 / unit;
        return whole + "." + tenths;
    }
}
