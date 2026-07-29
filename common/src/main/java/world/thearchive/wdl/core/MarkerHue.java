// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The choosable overlay marker hues: the only meaning-bearing overlay markers take a user-chosen hue, so a viewer with
 * a color-vision deficiency can cycle any marker to a distinct alternative without a separate global theme. Four brand
 * defaults ({@link #RED}, {@link #VIOLET}, {@link #TEAL}, {@link #AMBER}) sit on two surfaces that never draw together,
 * the in-world container outline and the map coverage overlay, and each role's row also offers the curated Okabe-Ito
 * alternatives ({@link #YELLOW}, {@link #BLUE}, {@link #REDDISH_PURPLE}, {@link #WHITE}). The full eight-hue Okabe-Ito
 * set is deliberately not re-expanded: its near-duplicates of the brand hues are dropped, and {@code WHITE} stands in
 * for the unusable black slot on dark overlay surfaces. {@link #YELLOW} is the one alternative that is itself a
 * near-duplicate, of {@link #AMBER} under red-green deficiency, so {@link #presetCycle} drops it from any row that
 * offers {@code AMBER} rather than dropping it from the palette outright.
 *
 * <p>{@link #TEAL} and {@link #AMBER} carry the same value as the like-named {@link BrandColors} token: the fixed
 * chrome accent and the themeable marker default are separate roles that happen to share a hue, so the overlay opens
 * on-brand and the chrome stays fixed wherever the user takes the marker.
 *
 * <p>Config stores the chosen constant directly; consumers read {@link #rgb()} at the draw site, with no RGB round-trip
 * through the file. The value is RGB only ({@code 0xFF} alpha is forced when drawn). Version-agnostic core: imports no
 * {@code net.minecraft.*} type and stays Java-8-clean. The fixed brand and semantic chrome lives in
 * {@link BrandColors}.
 */
public enum MarkerHue {
    RED(0xDE0000, Surface.OUTLINE),
    VIOLET(0x8A5CFF, Surface.OUTLINE),
    TEAL(0x5BC0BE, Surface.OVERLAY),
    AMBER(0xFFB84C, Surface.OVERLAY),
    YELLOW(0xF0E442, Surface.SHARED),
    BLUE(0x0072B2, Surface.SHARED),
    REDDISH_PURPLE(0xCC79A7, Surface.SHARED),
    WHITE(0xFFFFFF, Surface.SHARED);

    /**
     * Which drawn surface a hue is the brand default for, or {@link #SHARED} for an alternative that belongs to no
     * role. Only roles on the same surface can be confused for each other, so this is what a cycle excludes.
     */
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

    /** This hue's RGB value (alpha is forced to {@code 0xFF} at the draw site). */
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
