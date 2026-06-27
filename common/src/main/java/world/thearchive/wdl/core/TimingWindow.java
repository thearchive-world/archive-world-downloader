// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * A fixed-width sample window over elapsed nanos: accumulate samples, report the average and max once the window fills,
 * then reset. Holds no clock of its own; the caller supplies each elapsed sample and owns the log line, so one
 * accumulator serves callers whose reported payloads differ.
 */
public final class TimingWindow {
    private final int windowSize;
    private long count;
    private long totalNanos;
    private long maxNanos;

    public TimingWindow(int windowSize) {
        this.windowSize = windowSize;
    }

    /** Fold one sample in; returns true when the window has just filled and is due to be reported and reset. */
    public boolean record(long elapsedNanos) {
        count++;
        totalNanos += elapsedNanos;
        maxNanos = Math.max(maxNanos, elapsedNanos);
        return count >= windowSize;
    }

    public long count() {
        return count;
    }

    public long averageMicros() {
        return count == 0L ? 0L : totalNanos / count / 1000L;
    }

    public long maxMicros() {
        return maxNanos / 1000L;
    }

    public void reset() {
        count = 0L;
        totalNanos = 0L;
        maxNanos = 0L;
    }
}
