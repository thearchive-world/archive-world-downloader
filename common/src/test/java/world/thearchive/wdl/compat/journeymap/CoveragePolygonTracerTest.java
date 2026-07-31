// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.core.ChunkRectangleReducer;
import world.thearchive.wdl.core.OverlayHighlights;

/**
 * The tracer must carve each tone's holes so that its filled render (hull minus holes) is exactly the tone's rectangle
 * area, never more. The regression it guards: a suspect island inside a covered region inside more suspect made
 * JourneyMap's own hole grouping flood the covered tone with the suspect fill.
 */
final class CoveragePolygonTracerTest {
    private static final int MAX_RECTANGLES = 4096;

    private static long chunkKey(int cx, int cz) {
        return ((long) cz << 32) | (cx & 0xFFFFFFFFL);
    }

    private static int rectangleCount(int[] rectangles) {
        return rectangles.length / 4;
    }

    /**
     * The two tone-rectangle sets JourneyMapOverlayDriver.buildBatch selects: fine partition, or coarsened past the
     * cap.
     */
    private static int[][] toneRectangles(long[] saved, long[] covered) {
        OverlayHighlights.TonePartition part = OverlayHighlights.partitionTones(saved, covered);
        int[] coveredFine = ChunkRectangleReducer.reduce(part.covered());
        int[] suspectFine = ChunkRectangleReducer.reduce(part.suspect());
        if (rectangleCount(coveredFine) > MAX_RECTANGLES || rectangleCount(suspectFine) > MAX_RECTANGLES) {
            ChunkRectangleReducer.ToneRectangles coarse = ChunkRectangleReducer.coarsenTones(saved, covered,
                    MAX_RECTANGLES);
            return new int[][] { coarse.covered, coarse.suspect };
        }
        return new int[][] { coveredFine, suspectFine };
    }

    private static Area rectanglesToArea(int[] rectangles) {
        Area area = new Area();
        for (int i = 0; i < rectangles.length; i += 4) {
            int bx = rectangles[i] << 4;
            int bz = rectangles[i + 1] << 4;
            int bw = (rectangles[i + 2] - rectangles[i] + 1) << 4;
            int bh = (rectangles[i + 3] - rectangles[i + 1] + 1) << 4;
            area.add(new Area(new Rectangle(bx, bz, bw, bh)));
        }
        return area;
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

    /** The area the renderer fills for a tone: each hull minus its carved holes, unioned. */
    private static Area renderedFill(int[] rectangles) {
        Area fill = new Area();
        for (CoveragePolygonTracer.HoledRing ring : CoveragePolygonTracer.trace(rectangles)) {
            Area hull = ringToArea(ring.hull());
            for (int[] hole : ring.holes()) {
                hull.subtract(ringToArea(hole));
            }
            fill.add(hull);
        }
        return fill;
    }

    private static void assertTonesRenderExactly(String name, long[] saved, long[] covered) {
        int[][] tones = toneRectangles(saved, covered);
        for (int i = 0; i < 2; i++) {
            Area rendered = renderedFill(tones[i]);
            Area expected = rectanglesToArea(tones[i]);
            Area diff = new Area(rendered);
            diff.exclusiveOr(expected);
            assertTrue(diff.isEmpty(),
                    name + " tone " + (i == 0 ? "covered" : "suspect") + " render does not match its rectangles");
        }
        Area coveredFill = renderedFill(tones[0]);
        Area suspectFill = renderedFill(tones[1]);
        Area overlap = new Area(coveredFill);
        overlap.intersect(suspectFill);
        assertTrue(overlap.isEmpty(), name + " covered and suspect fills overlap");
    }

    private static long[] array(LongOpenHashSet set) {
        return set.toLongArray();
    }

    @Test
    void suspectDonutSurroundingCoveredBlob() {
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 30; cx++) {
            for (int cz = 0; cz < 30; cz++) {
                saved.add(chunkKey(cx, cz));
                if (cx >= 8 && cx < 22 && cz >= 8 && cz < 22) {
                    covered.add(chunkKey(cx, cz));
                }
            }
        }
        assertTonesRenderExactly("donut", array(saved), array(covered));
    }

    @Test
    void nestedSuspectIslandInsideCoveredRing() {
        // The reported bug: suspect center, covered ring, suspect outer. The covered-ring hole must stay with the
        // outer suspect hull, not the inner suspect island, or the outer hull renders uncarved over the covered tone.
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 40; cx++) {
            for (int cz = 0; cz < 40; cz++) {
                saved.add(chunkKey(cx, cz));
                int dx = cx - 20;
                int dz = cz - 20;
                int d2 = dx * dx + dz * dz;
                if (d2 >= 25 && d2 <= 144) {
                    covered.add(chunkKey(cx, cz));
                }
            }
        }
        assertTonesRenderExactly("nested", array(saved), array(covered));
    }

    @Test
    void deeperNestingAlternatingTones() {
        // Concentric bands alternating covered and suspect, so holes nest several deep.
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 60; cx++) {
            for (int cz = 0; cz < 60; cz++) {
                saved.add(chunkKey(cx, cz));
                int dx = cx - 30;
                int dz = cz - 30;
                int d2 = dx * dx + dz * dz;
                int band = (int) Math.floor(Math.sqrt(d2) / 5.0);
                if (band % 2 == 1 && d2 <= 900) {
                    covered.add(chunkKey(cx, cz));
                }
            }
        }
        assertTonesRenderExactly("deep-nested", array(saved), array(covered));
    }

    @Test
    void swissCheeseCoveredIslands() {
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 40; cx++) {
            for (int cz = 0; cz < 40; cz++) {
                saved.add(chunkKey(cx, cz));
            }
        }
        int[][] centers = { { 10, 10 }, { 30, 10 }, { 10, 30 }, { 30, 30 }, { 20, 20 } };
        for (int[] center : centers) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    covered.add(chunkKey(center[0] + dx, center[1] + dz));
                }
            }
        }
        assertTonesRenderExactly("swiss", array(saved), array(covered));
    }

    @Test
    void fastFlightSparseDiscsInSolidBand() {
        int renderDistance = 12;
        int sendRadius = 6;
        int centerSpacing = 5;
        int length = 200;
        LongOpenHashSet saved = new LongOpenHashSet();
        for (int cx = 0; cx <= length; cx++) {
            for (int cz = -renderDistance; cz <= renderDistance; cz++) {
                saved.add(chunkKey(cx, cz));
            }
        }
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int center = 0; center <= length; center += centerSpacing) {
            for (int dx = -sendRadius; dx <= sendRadius; dx++) {
                for (int dz = -sendRadius; dz <= sendRadius; dz++) {
                    if (dx * dx + dz * dz <= sendRadius * sendRadius) {
                        covered.add(chunkKey(center + dx, dz));
                    }
                }
            }
        }
        assertTonesRenderExactly("fast-sparse", array(saved), array(covered));
    }

    @Test
    void singleEnclosedSuspectGap() {
        // One suspect chunk fully enclosed by covered, itself enclosed by the saved bound: the minimal trigger.
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 9; cx++) {
            for (int cz = 0; cz < 9; cz++) {
                saved.add(chunkKey(cx, cz));
                if (!(cx == 4 && cz == 4)) {
                    covered.add(chunkKey(cx, cz));
                }
            }
        }
        assertTonesRenderExactly("single-gap", array(saved), array(covered));
    }

    @Test
    void coarsenedToneRectanglesTraceWithoutOverlap() {
        // The coarsen->trace seam the >4096 cap would take: a covered ring around a suspect core, coarsened onto a
        // small grid, must still trace to disjoint fills that each match their coarse rectangles exactly.
        LongOpenHashSet saved = new LongOpenHashSet();
        LongOpenHashSet covered = new LongOpenHashSet();
        for (int cx = 0; cx < 24; cx++) {
            for (int cz = 0; cz < 24; cz++) {
                saved.add(chunkKey(cx, cz));
                int dx = cx - 12;
                int dz = cz - 12;
                int d2 = dx * dx + dz * dz;
                if (d2 >= 16 && d2 <= 100) {
                    covered.add(chunkKey(cx, cz));
                }
            }
        }
        ChunkRectangleReducer.ToneRectangles coarse = ChunkRectangleReducer.coarsenTones(array(saved), array(covered),
                16);
        Area coveredFill = renderedFill(coarse.covered);
        Area suspectFill = renderedFill(coarse.suspect);
        Area coveredDiff = new Area(coveredFill);
        coveredDiff.exclusiveOr(rectanglesToArea(coarse.covered));
        Area suspectDiff = new Area(suspectFill);
        suspectDiff.exclusiveOr(rectanglesToArea(coarse.suspect));
        assertTrue(coveredDiff.isEmpty(), "coarse covered fill matches its rectangles");
        assertTrue(suspectDiff.isEmpty(), "coarse suspect fill matches its rectangles");
        Area overlap = new Area(coveredFill);
        overlap.intersect(suspectFill);
        assertTrue(overlap.isEmpty(), "coarse covered and suspect fills do not overlap");
    }
}
