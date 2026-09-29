// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public enum MarkerHue {
    RED(0xDE0000, Surface.OUTLINE),
    VIOLET(0x8A5CFF, Surface.OUTLINE),
    TEAL(0x5BC0BE, Surface.OVERLAY),
    AMBER(0xFFB84C, Surface.OVERLAY),
    YELLOW(0xF0E442, Surface.SHARED),
    BLUE(0x0072B2, Surface.SHARED),
    REDDISH_PURPLE(0xCC79A7, Surface.SHARED),
    WHITE(0xFFFFFF, Surface.SHARED);

    private enum Surface {
        OUTLINE,
        OVERLAY,
        SHARED
    }

    private final int rgb;
    private final Surface surface;

    MarkerHue(int rgb, Surface surface) {
        this.rgb = rgb;
        this.surface = surface;
    }

    /** This hue's RGB value. */
    public int rgb() {
        return rgb;
    }

    /**
     * The preset cycle a marker row steps: the row's own brand default first, then the curated Okabe-Ito alternatives,
     * then the brand defaults of the other surface. Deliberately not {@link #values()}, which would step the sibling
     * role's brand default, the one hue guaranteed to read as the other role on the same surface. This keeps the two
     * roles' defaults apart; it does not make them unable to collide, since the rest of the steps are shared and a row
     * can still be pointed at whatever the sibling currently holds. The other surface's defaults are offered because
     * those roles never appear in the same view.
     *
     * <p>A cycle offering {@link #AMBER} drops {@link #YELLOW}: under red-green deficiency the two sit close enough
     * that a row naming both, without showing either, offers no distinction a viewer can act on. Pass a role's brand
     * default.
     */
    public static List<MarkerHue> presetCycle(MarkerHue brandDefault) {
        List<MarkerHue> cycle = new ArrayList<>();
        cycle.add(brandDefault);
        for (MarkerHue hue : values()) {
            if (hue.surface == Surface.SHARED) {
                cycle.add(hue);
            }
        }
        for (MarkerHue hue : values()) {
            if (hue.surface != Surface.SHARED && hue.surface != brandDefault.surface) {
                cycle.add(hue);
            }
        }
        if (cycle.contains(AMBER)) {
            cycle.remove(YELLOW);
        }
        return Collections.unmodifiableList(cycle);
    }
}
