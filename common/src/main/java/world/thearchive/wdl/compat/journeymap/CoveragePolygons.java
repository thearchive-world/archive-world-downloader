// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap;

import java.util.ArrayList;
import java.util.List;
import journeymap.client.api.model.MapPolygon;
import journeymap.client.api.model.ShapeProperties;
import net.minecraft.util.math.BlockPos;

final class CoveragePolygons {
    private static final int OVERLAY_Y = 64;

    private static final float FILL_OPACITY = 100f / 255f;

    private CoveragePolygons() {}

    /**
     * The default {@link ShapeProperties} carries a two-pixel opaque black outline, so the stroke opacity and width are
     * both zeroed here or a black grid would draw between adjacent rectangles.
     */
    static ShapeProperties toneStyle(int rgb) {
        return new ShapeProperties()
                .setFillColor(rgb)
                .setFillOpacity(FILL_OPACITY)
                .setStrokeOpacity(0f)
                .setStrokeWidth(0f);
    }

    /**
     * This band feeds JourneyMap the rectangles rather than the merged holed hulls {@link CoveragePolygonTracer}
     * produces. The 1.4 API has no holed-polygon type at all, and whether the build serving this band renders the
     * six-argument holes overload is unverified, so the rectangle path is what ships: it needs neither, and a
     * JourneyMap that ignores holes would flood the tone over the gaps rather than leave them uncovered.
     */
    static List<MapPolygon> polygons(int[] rectangles) {
        List<MapPolygon> result = new ArrayList<>(rectangles.length / 4);
        for (int i = 0; i < rectangles.length; i += 4) {
            int x0 = rectangles[i] << 4;
            int z0 = rectangles[i + 1] << 4;
            int x1 = (rectangles[i + 2] + 1) << 4;
            int z1 = (rectangles[i + 3] + 1) << 4;
            // Counter-clockwise in screen space (north-west, south-west, south-east, north-east), which is what makes
            // the fill front-facing. JourneyMap draws the fill as a GL_POLYGON without disabling face culling, so a
            // clockwise ring is culled away while the stroke, a GL_LINE_STRIP, is not: the overlay then renders as an
            // outline with no tint, or as nothing at all when the stroke is suppressed the way toneStyle suppresses it.
            result.add(toPolygon(new int[] { x0, z0, x0, z1, x1, z1, x1, z0 }));
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
