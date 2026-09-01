// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import java.util.ArrayList;
import java.util.List;
import journeymap.client.api.model.MapPolygon;
import journeymap.client.api.model.ShapeProperties;
import net.minecraft.util.math.BlockPos;

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
     * One convex JourneyMap polygon per coverage rectangle, four ints per rectangle in chunk space (inclusive
     * {@code minChunkX, minChunkZ, maxChunkX, maxChunkZ}). The rectangles are disjoint and abut, so their translucent
     * fills tile seamlessly and the uncovered gaps stay uncovered. This band feeds JourneyMap the rectangles rather
     * than the merged holed hulls {@link CoveragePolygonTracer} produces. The 1.x API has no holed-polygon type at all,
     * and whether the build serving this band renders the six-argument holes overload is unverified, so the rectangle
     * path is what ships: it needs neither, and a JourneyMap that ignores holes would flood the tone over the gaps
     * rather than leave them uncovered. An empty input yields an empty list.
     */
    static List<MapPolygon> polygons(int[] rectangles) {
        List<MapPolygon> result = new ArrayList<>(rectangles.length / 4);
        for (int i = 0; i < rectangles.length; i += 4) {
            int x0 = rectangles[i] << 4;
            int z0 = rectangles[i + 1] << 4;
            int x1 = (rectangles[i + 2] + 1) << 4;
            int z1 = (rectangles[i + 3] + 1) << 4;
            result.add(toPolygon(new int[] { x0, z0, x1, z0, x1, z1, x0, z1 }));
        }
        return result;
    }

    private static MapPolygon toPolygon(int[] ring) {
        List<BlockPos> points = new ArrayList<>(ring.length / 2);
        for (int i = 0; i < ring.length; i += 2) {
            points.add(new BlockPos(ring[i], OVERLAY_Y, ring[i + 1]));
        }
        // Called directly rather than reflectively. The band whose restore this is compiles against Mojmap and had to
        // reach a constructor the API jar declares in classic MCP names; this band is classic MCP itself, so the
        // MapPolygon(List<net.minecraft.util.math.BlockPos>) constructor resolves at compile time.
        return new MapPolygon(points);
    }
}
