// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * A non-negative time span decomposed into hours, minutes, and seconds, with two-digit zero-padding for a component.
 */
public final class ElapsedTime {
    private final long hours;
    private final long minutes;
    private final long seconds;

    private ElapsedTime(long hours, long minutes, long seconds) {
        this.hours = hours;
        this.minutes = minutes;
        this.seconds = seconds;
    }

    /** Decompose a millisecond span into hours, minutes, and seconds; a negative span clamps to zero. */
    static ElapsedTime ofMillis(long millis) {
        return ofSeconds(Math.max(0L, millis) / 1000L);
    }

    /** Decompose a second span into hours, minutes, and seconds; a negative span clamps to zero. */
    public static ElapsedTime ofSeconds(long totalSeconds) {
        long total = Math.max(0L, totalSeconds);
        return new ElapsedTime(total / 3600L, (total % 3600L) / 60L, total % 60L);
    }

    public long hours() {
        return hours;
    }

    public long minutes() {
        return minutes;
    }

    public long seconds() {
        return seconds;
    }

    long totalMinutes() {
        return hours * 60L + minutes;
    }

    /** Zero-pad a component to two digits (9 becomes 09, 10 stays 10). */
    public static String pad2(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
