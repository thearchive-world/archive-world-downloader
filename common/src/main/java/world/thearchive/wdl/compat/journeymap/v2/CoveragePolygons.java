// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.compat.journeymap.v2;

import java.util.ArrayList;
import java.util.List;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.core.BlockPos;

import world.thearchive.wdl.compat.journeymap.CoveragePolygonTracer;

final class CoveragePolygons {
    private static final int OVERLAY_Y = 64;

    private static final float FILL_OPACITY = 100f / 255f;

    private CoveragePolygons() {}

    /**
     * The default {@link ShapeProperties} carries a two-pixel opaque black outline, so the stroke opacity and width are
     * both zeroed here.
     */
    static ShapeProperties toneStyle(int rgb) {
        return new ShapeProperties()
                .setFillColor(rgb)
                .setFillOpacity(FILL_OPACITY)
                .setStrokeOpacity(0f)
                .setStrokeWidth(0f);
    }

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
