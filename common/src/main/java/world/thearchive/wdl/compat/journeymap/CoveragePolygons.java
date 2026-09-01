// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import java.util.ArrayList;
import java.util.List;
import journeymap.client.api.model.MapPolygon;
import journeymap.client.api.model.ShapeProperties;
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
     * One convex JourneyMap polygon per coverage rectangle, four ints per rectangle in chunk space (inclusive
     * {@code minChunkX, minChunkZ, maxChunkX, maxChunkZ}). The rectangles are disjoint and abut, so their translucent
     * fills tile seamlessly and the uncovered gaps stay uncovered. This band feeds JourneyMap the rectangles rather
     * than the merged holed hulls {@link CoveragePolygonTracer} produces, because JourneyMap 5.7.0 neither subtracts a
     * hole nor fills a concave polygon: it floods the tone over the gaps and streaks the tessellation to a corner. An
     * empty input yields an empty list.
     */
    static List<MapPolygon> polygons(int[] rectangles) {
        List<MapPolygon> result = new ArrayList<>(rectangles.length / 4);
        for (int i = 0; i < rectangles.length; i += 4) {
            int x0 = rectangles[i] << 4;
            int z0 = rectangles[i + 1] << 4;
            int x1 = (rectangles[i + 2] + 1) << 4;
            int z1 = (rectangles[i + 3] + 1) << 4;
            // Counter-clockwise in screen space (north-west, south-west, south-east, north-east), which is what
            // makes the fill front-facing. JourneyMap draws the fill as a GL_POLYGON without disabling face
            // culling, so a clockwise ring is culled wherever the surrounding pass leaves culling on: the minimap
            // draws inside the in-world HUD pass and the full-screen map inside a GUI screen, which is why a
            // clockwise ring can survive on one and vanish on the other.
            result.add(toPolygon(new int[] { x0, z0, x0, z1, x1, z1, x1, z0 }));
        }
        return result;
    }

    private static MapPolygon toPolygon(int[] ring) {
        List<BlockPos> points = new ArrayList<>(ring.length / 2);
        for (int i = 0; i < ring.length; i += 2) {
            points.add(new BlockPos(ring[i], OVERLAY_Y, ring[i + 1]));
        }
        // The pinned JourneyMap API jar carries its MapPolygon(List) BlockPos parameter in mappings this plain
        // compile-only pin does not bridge to this band's Mojmap, so it is built reflectively; at runtime JourneyMap
        // is loaded in the running mappings, where the constructor resolves.
        try {
            return MapPolygon.class.getConstructor(List.class).newInstance(points);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the JourneyMap MapPolygon(List) constructor is unavailable", e);
        }
    }
}
