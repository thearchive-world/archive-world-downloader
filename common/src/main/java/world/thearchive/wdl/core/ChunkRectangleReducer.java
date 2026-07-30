// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reduces a set of chunk positions (packed as {@code ChunkPos.asLong}) to a small list of chunk-space rectangles, so a
 * downstream area union stays cheap even when a real download holds thousands of chunks. MC-free and Java-8-clean:
 * decode is a bit operation, not a {@code ChunkPos} call.
 */
public final class ChunkRectangleReducer {
    private ChunkRectangleReducer() {}

    private static int chunkX(long packed) {
        return (int) packed;
    }

    private static int chunkZ(long packed) {
        return (int) (packed >> 32);
    }

    /** Row-RLE then vertical coalescing of equal x-runs. Returns inclusive rectangles, 4 ints each. */
    public static int[] reduce(long[] chunkPositions) {
        if (chunkPositions.length == 0) {
            return new int[0];
        }
        // Bucket by z-row, collect x-runs per row, then merge a run downward while an identical run continues.
        Map<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        for (long packed : chunkPositions) {
            rows.computeIfAbsent(chunkZ(packed), key -> new TreeSet<>()).add(chunkX(packed));
        }
        // rowRuns: z -> list of [minX, maxX]
        Map<Integer, List<int[]>> rowRuns = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<Integer>> entry : rows.entrySet()) {
            List<int[]> runs = new ArrayList<>();
            boolean started = false;
            int runStart = 0;
            int previous = 0;
            for (int x : entry.getValue()) {
                if (!started) {
                    runStart = x;
                    started = true;
                } else if (x != previous + 1) {
                    runs.add(new int[] { runStart, previous });
                    runStart = x;
                }
                previous = x;
            }
            if (started) {
                runs.add(new int[] { runStart, previous });
            }
            rowRuns.put(entry.getKey(), runs);
        }
        // Greedy vertical coalescing: extend a run downward through consecutive z-rows that carry the same run.
        List<int[]> rectangles = new ArrayList<>();
        Map<Integer, boolean[]> consumed = new HashMap<>();
        List<Map.Entry<Integer, List<int[]>>> rowEntries = new ArrayList<>(rowRuns.entrySet());
        for (int zi = 0; zi < rowEntries.size(); zi++) {
            Map.Entry<Integer, List<int[]>> rowEntry = rowEntries.get(zi);
            int z = rowEntry.getKey();
            List<int[]> runs = rowEntry.getValue();
            boolean[] used = consumed.computeIfAbsent(z, key -> new boolean[runs.size()]);
            for (int ri = 0; ri < runs.size(); ri++) {
                if (used[ri]) {
                    continue;
                }
                int minX = runs.get(ri)[0];
                int maxX = runs.get(ri)[1];
                int maxZ = z;
                int zj = zi + 1;
                while (zj < rowEntries.size() && rowEntries.get(zj).getKey() == maxZ + 1) {
                    Map.Entry<Integer, List<int[]>> nextEntry = rowEntries.get(zj);
                    int zc = nextEntry.getKey();
                    List<int[]> zcRuns = nextEntry.getValue();
                    boolean[] usedZc = consumed.computeIfAbsent(zc, key -> new boolean[zcRuns.size()]);
                    int matched = matchingRun(zcRuns, usedZc, minX, maxX);
                    if (matched < 0) {
                        break;
                    }
                    usedZc[matched] = true;
                    maxZ = zc;
                    zj++;
                }
                rectangles.add(new int[] { minX, z, maxX, maxZ });
            }
        }
        return flatten(rectangles);
    }

    /** Two disjoint coarse tone rectangle sets (chunk-space, 4 ints each). */
    public static final class ToneRectangles {
        public final int[] covered;
        public final int[] suspect;

        ToneRectangles(int[] covered, int[] suspect) {
            this.covered = covered;
            this.suspect = suspect;
        }
    }

    /**
     * Coarsen the coverage into two DISJOINT coarse tone rectangle sets, each with at most {@code maxCellsPerTone}
     * rectangles, for the pathological fragmented tail. Both tone sets are needed here because the classification is
     * cross-tone: a grid cell draws covered when every one of its {@code cell*cell} chunk slots is saved and more than
     * half of them are covered, suspect when it holds any saved-not-covered chunk and did not draw covered, and nothing
     * otherwise. Requiring a full cell for the covered hue keeps it off not-fully-saved ground; taking the covered
     * majority rather than demanding every slot be covered keeps a mostly-covered but fragmented cell from flipping to
     * suspect, while an even split still errs toward suspect. The two branches are exclusive per cell, so the sets
     * never overlap; a per-tone single-set grid would double-paint a mixed cell, so the two tones must be coarsened
     * together, not by two independent calls.
     */
    public static ToneRectangles coarsenTones(long[] saved, long[] covered, int maxCellsPerTone) {
        int cap = Math.max(1, maxCellsPerTone);
        if (saved.length == 0) {
            return new ToneRectangles(new int[0], new int[0]);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long chunkPos : saved) { // saved bounds both tones: covered∩saved and saved\covered are subsets of saved
            minX = Math.min(minX, chunkX(chunkPos));
            minZ = Math.min(minZ, chunkZ(chunkPos));
            maxX = Math.max(maxX, chunkX(chunkPos));
            maxZ = Math.max(maxZ, chunkZ(chunkPos));
        }
        int perAxis = Math.max(1, (int) Math.floor(Math.sqrt(cap)));
        int cell = Math.max(1, (int) Math.ceil((double) Math.max(maxX - minX + 1, maxZ - minZ + 1) / perAxis));
        LongOpenHashSet coveredSet = new LongOpenHashSet(covered);
        Map<Long, int[]> counts = new HashMap<>(); // cellKey -> {savedCount, coveredInSaved}
        for (long chunkPos : saved) {
            int cx = (chunkX(chunkPos) - minX) / cell;
            int cz = (chunkZ(chunkPos) - minZ) / cell;
            long key = ((long) cx & 0xFFFFFFFFL) | ((long) cz & 0xFFFFFFFFL) << 32;
            int[] cellCounts = counts.computeIfAbsent(key, absentKey -> new int[2]);
            cellCounts[0]++;
            if (coveredSet.contains(chunkPos)) {
                cellCounts[1]++;
            }
        }
        long full = (long) cell * cell; // long: cell can exceed 46340 on a far-traveled extent, int would overflow
        List<int[]> coveredRectangles = new ArrayList<>();
        List<int[]> suspectRectangles = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : counts.entrySet()) {
            long cellKey = entry.getKey();
            int rx0 = minX + (int) cellKey * cell;
            int rz0 = minZ + (int) (cellKey >> 32) * cell;
            int[] rectangle = { rx0, rz0, rx0 + cell - 1, rz0 + cell - 1 };
            int savedCount = entry.getValue()[0];
            int coveredCount = entry.getValue()[1];
            // The else is load-bearing: a covered-majority cell also satisfies the suspect test, so splitting
            // these into separate ifs would double-paint the cell into both tone sets.
            if (savedCount == full && (long) coveredCount * 2 > full) {
                coveredRectangles.add(rectangle);
            } else if (savedCount > coveredCount) {
                suspectRectangles.add(rectangle);
            }
        }
        return new ToneRectangles(flatten(coveredRectangles), flatten(suspectRectangles));
    }

    private static int matchingRun(List<int[]> runs, boolean[] used, int minX, int maxX) {
        for (int i = 0; i < runs.size(); i++) {
            if (!used[i] && runs.get(i)[0] == minX && runs.get(i)[1] == maxX) {
                return i;
            }
        }
        return -1;
    }

    private static int[] flatten(List<int[]> rectangles) {
        int[] out = new int[rectangles.size() * 4];
        for (int i = 0; i < rectangles.size(); i++) {
            System.arraycopy(rectangles.get(i), 0, out, i * 4, 4);
        }
        return out;
    }
}
