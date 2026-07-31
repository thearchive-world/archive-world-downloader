// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import java.util.ArrayList;
import java.util.List;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.core.BlockPos;

/**
 * Turns chunk-space coverage rectangles into JourneyMap polygon hulls and builds the fill-only shape style for a
 * coverage tone. The rectangle merge and hole tracing is {@link CoveragePolygonTracer}, pure geometry with no MC or
 * render state, so the driver runs this off the client thread and only shows the resulting polygons back on it.
 */
final class CoveragePolygons {
    // The horizontal plane the coverage polygons are placed on. JourneyMap projects overlays onto the top-down
    // map by their x and z, so the y is cosmetic here; one fixed value keeps every tone on the same plane.
    private static final int OVERLAY_Y = 64;

    // The coverage fill alpha as a 0..1 fraction, the 100/255 the sibling overlay integration draws at, so the
    // two read as the same highlight.
    private static final float FILL_OPACITY = 100f / 255f;

    private CoveragePolygons() {}

    /**
     * The fill-only shape style for a tone: {@code rgb} filled at the shared coverage opacity with the stroke fully
     * suppressed. The default {@link ShapeProperties} carries a two-pixel opaque black outline, so the stroke opacity
     * and width are both zeroed here or a black grid would draw between adjacent rectangles.
     */
    static ShapeProperties toneStyle(int rgb) {
        return new ShapeProperties()
                .setFillColor(rgb)
                .setFillOpacity(FILL_OPACITY)
                .setStrokeOpacity(0f)
                .setStrokeWidth(0f);
    }

    /**
     * The merged polygon hulls covering {@code rects}, four ints per rectangle in chunk space (inclusive
     * {@code minChunkX, minChunkZ, maxChunkX, maxChunkZ}); adjacent rectangles merge into a single hull with its
     * enclosed gaps cut as holes. An empty input yields an empty list.
     */
    static List<MapPolygonWithHoles> hulls(int[] rectangles) {
        List<CoveragePolygonTracer.HoledRing> rings = CoveragePolygonTracer.trace(rectangles);
        List<MapPolygonWithHoles> result = new ArrayList<>(rings.size());
        for (CoveragePolygonTracer.HoledRing ring : rings) {
            List<MapPolygon> holes = new ArrayList<>(ring.holes().size());
            for (int[] hole : ring.holes()) {
                holes.add(toPolygon(hole));
            }
            result.add(new MapPolygonWithHoles(toPolygon(ring.hull()), holes));
        }
        return result;
    }

    private static MapPolygon toPolygon(int[] ring) {
        List<BlockPos> points = new ArrayList<>(ring.length / 2);
        for (int i = 0; i < ring.length; i += 2) {
            points.add(new BlockPos(ring[i], OVERLAY_Y, ring[i + 1]));
        }
        return new MapPolygon(points);
    }
}
