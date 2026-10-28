// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import com.google.common.collect.ImmutableList;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

public final class CoveragePolygonTracer {
    private CoveragePolygonTracer() {}

    public static final class HoledRing {
        private final int[] hull;
        private final List<int[]> holes;

        HoledRing(int[] hull, List<int[]> holes) {
            this.hull = hull;
            this.holes = holes;
        }

        public int[] hull() {
            return hull;
        }

        public List<int[]> holes() {
            return holes;
        }
    }

    public static List<HoledRing> trace(int[] chunkRectangles) {
        if (chunkRectangles.length == 0) {
            return ImmutableList.of();
        }
        Area area = new Area();
        for (int i = 0; i < chunkRectangles.length; i += 4) {
            int blockX = chunkRectangles[i] << 4;
            int blockZ = chunkRectangles[i + 1] << 4;
            int blockWidth = (chunkRectangles[i + 2] - chunkRectangles[i] + 1) << 4;
            int blockHeight = (chunkRectangles[i + 3] - chunkRectangles[i + 1] + 1) << 4;
            area.add(new Area(new Rectangle(blockX, blockZ, blockWidth, blockHeight)));
        }
        return traceArea(area);
    }

    private static List<HoledRing> traceArea(Area area) {
        List<int[]> hullRings = new ArrayList<>();
        List<int[]> holeRings = new ArrayList<>();
        for (int[] ring : contours(area)) {
            if (ring.length < 6) {
                continue;
            }
            if (isHole(ring)) {
                holeRings.add(ring);
            } else {
                hullRings.add(ring);
            }
        }
        List<Area> hullAreas = new ArrayList<>(hullRings.size());
        double[] hullSizes = new double[hullRings.size()];
        List<List<int[]>> holesByHull = new ArrayList<>(hullRings.size());
        for (int i = 0; i < hullRings.size(); i++) {
            hullAreas.add(ringToArea(hullRings.get(i)));
            hullSizes[i] = ringArea(hullRings.get(i));
            holesByHull.add(new ArrayList<>());
        }
        for (int[] hole : holeRings) {
            Area holeArea = ringToArea(hole);
            int owner = -1;
            double ownerSize = Double.MAX_VALUE;
            for (int i = 0; i < hullRings.size(); i++) {
                if (hullSizes[i] >= ownerSize) {
                    continue; // a larger hull cannot beat the innermost owner already found
                }
                Area outside = new Area(holeArea);
                outside.subtract(hullAreas.get(i));
                if (outside.isEmpty()) {
                    owner = i;
                    ownerSize = hullSizes[i];
                }
            }
            if (owner >= 0) {
                holesByHull.get(owner).add(hole);
            }
        }
        List<HoledRing> result = new ArrayList<>(hullRings.size());
        for (int i = 0; i < hullRings.size(); i++) {
            result.add(new HoledRing(hullRings.get(i), holesByHull.get(i)));
        }
        return result;
    }

    private static List<int[]> contours(Area area) {
        List<int[]> rings = new ArrayList<>();
        List<int[]> current = new ArrayList<>();
        PathIterator iterator = area.getPathIterator(null);
        float[] points = new float[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(points);
            if (type == PathIterator.SEG_MOVETO) {
                if (!current.isEmpty()) {
                    rings.add(flattenSimplified(current));
                    current = new ArrayList<>();
                }
                current.add(new int[] { Math.round(points[0]), Math.round(points[1]) });
            } else if (type == PathIterator.SEG_LINETO) {
                current.add(new int[] { Math.round(points[0]), Math.round(points[1]) });
            }
            iterator.next();
        }
        if (!current.isEmpty()) {
            rings.add(flattenSimplified(current));
        }
        return rings;
    }

    private static int[] flattenSimplified(List<int[]> points) {
        List<int[]> kept;
        if (points.size() < 3) {
            kept = points;
        } else {
            kept = new ArrayList<>();
            int[] previous2 = points.get(0);
            int[] previous1 = points.get(1);
            kept.add(previous2);
            for (int index = 2; index < points.size(); index++) {
                int[] next = points.get(index);
                if (previous2[0] == previous1[0] && previous1[0] == next[0]) {
                    previous1 = next;
                } else if (previous2[1] == previous1[1] && previous1[1] == next[1]) {
                    previous1 = next;
                } else {
                    kept.add(previous1);
                    previous2 = previous1;
                    previous1 = next;
                }
            }
            kept.add(previous1);
        }
        int[] flat = new int[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[i * 2] = kept.get(i)[0];
            flat[i * 2 + 1] = kept.get(i)[1];
        }
        return flat;
    }

    private static boolean isHole(int[] ring) {
        long sum = 0;
        int ax = ring[ring.length - 2];
        int az = ring[ring.length - 1];
        for (int i = 0; i < ring.length; i += 2) {
            int bx = ring[i];
            int bz = ring[i + 1];
            sum += (long) (bx - ax) * (bz + az);
            ax = bx;
            az = bz;
        }
        return sum < 0;
    }

    private static double ringArea(int[] ring) {
        long twice = 0;
        int ax = ring[ring.length - 2];
        int az = ring[ring.length - 1];
        for (int i = 0; i < ring.length; i += 2) {
            int bx = ring[i];
            int bz = ring[i + 1];
            twice += (long) ax * bz - (long) bx * az;
            ax = bx;
            az = bz;
        }
        return Math.abs(twice) / 2.0;
    }

    private static Area ringToArea(int[] ring) {
        int count = ring.length / 2;
        int[] xs = new int[count];
        int[] zs = new int[count];
        for (int i = 0; i < count; i++) {
            xs[i] = ring[i * 2];
            zs[i] = ring[i * 2 + 1];
        }
        return new Area(new Polygon(xs, zs, count));
    }
}
