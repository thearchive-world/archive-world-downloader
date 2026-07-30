// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;

/**
 * The two-tone coverage overlay's color logic, split out MC-free so it is headless-testable without a compat binding's
 * map-mod types. {@link #tag} labels each saved chunk covered or suspect against the covered set, and
 * {@link #toneColor} resolves a label to its ARGB. The provider bindings wire these into their per-chunk fill or
 * merged-polygon rendering.
 */
public final class OverlayHighlights {
    /** The per-chunk label for a chunk the recording path brought within entity range: the user's covered hue. */
    static final long COVERED = 1L;

    /** The per-chunk label for a saved chunk never within entity range: drawn in the user's suspect hue. */
    static final long SUSPECT = 0L;

    private OverlayHighlights() {}

    /**
     * Label each saved chunk {@link #COVERED} or {@link #SUSPECT} against the covered set: suspect is saved and not
     * covered, computed inline by membership rather than materializing a set difference. Covered positions not in
     * {@code saved} are dropped, since the overlay only draws saved chunks. Returns an empty map when nothing is saved.
     */
    public static Long2LongMap tag(long[] saved, long[] covered) {
        if (saved.length == 0) {
            return Long2LongMaps.EMPTY_MAP;
        }
        LongOpenHashSet coveredSet = new LongOpenHashSet(covered);
        Long2LongOpenHashMap tagged = new Long2LongOpenHashMap(saved.length);
        for (long chunkPos : saved) {
            tagged.put(chunkPos, coveredSet.contains(chunkPos) ? COVERED : SUSPECT);
        }
        return tagged;
    }

    /**
     * The ARGB for a labeled chunk at the given overlay alpha: the live {@code coveredRgb} marker hue for a covered
     * chunk, the live {@code suspectRgb} marker hue for any other label.
     */
    public static int toneColor(long label, int coveredRgb, int suspectRgb, int alpha) {
        return alpha | (label == COVERED ? coveredRgb : suspectRgb);
    }

    /** The two drawn tone sets: {@code covered = saved ∩ covered}, {@code suspect = saved \ covered}. */
    public static final class TonePartition {
        private final long[] covered;
        private final long[] suspect;

        TonePartition(long[] covered, long[] suspect) {
            this.covered = covered;
            this.suspect = suspect;
        }

        public long[] covered() {
            return covered;
        }

        public long[] suspect() {
            return suspect;
        }
    }

    /**
     * Split the saved set into covered and suspect against the covered set, {@code saved}-anchored so a covered
     * position not in {@code saved} is dropped, exactly as {@link #tag} labels. In the calibration mirror
     * ({@code covered} mirrors {@code saved}) every saved chunk is covered, so suspect is empty.
     */
    public static TonePartition partitionTones(long[] saved, long[] covered) {
        if (saved.length == 0) {
            return new TonePartition(new long[0], new long[0]);
        }
        LongOpenHashSet coveredSet = new LongOpenHashSet(covered);
        long[] coveredOut = new long[saved.length];
        long[] suspectOut = new long[saved.length];
        int coveredCount = 0;
        int suspectCount = 0;
        for (long chunkPos : saved) {
            if (coveredSet.contains(chunkPos)) {
                coveredOut[coveredCount++] = chunkPos;
            } else {
                suspectOut[suspectCount++] = chunkPos;
            }
        }
        return new TonePartition(Arrays.copyOf(coveredOut, coveredCount), Arrays.copyOf(suspectOut, suspectCount));
    }
}
