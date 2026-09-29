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
     * The preset cycle a marker row steps: the brand default passed in first, then the {@code SHARED} hues, then the
     * other surface's two brand defaults. Deliberately not {@link #values()}, which would step the other brand default
     * on the same surface.
     *
     * <p>A cycle offering {@link #AMBER} drops {@link #YELLOW}. Pass {@link #RED}, {@link #VIOLET}, {@link #TEAL} or
     * {@link #AMBER}.
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
