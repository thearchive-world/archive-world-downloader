// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The mod's fixed brand and semantic colors. Every surface that paints chrome or a status color, the download screen,
 * the rendered HUD, the in-world container outline, and the coverage overlay, reads these named tokens rather than
 * hard-coding a hue, so the identity lives in one place. The accent, text-on-surface, and semantic-state roles are
 * fixed (not user-themeable) for cross-surface cohesion and contrast safety; pick a role before adding a color, and do
 * not let two roles share a hue.
 *
 * <p>Each token is RGB only: {@link #opaque(int)} forces the {@code 0xFF} alpha. Version-agnostic core: imports no
 * {@code net.minecraft.*} type and stays Java-8-clean. The choosable overlay marker hues live in {@link MarkerHue}.
 */
public final class BrandColors {
    /**
     * Brand and hot/active accent: HUD progress fill, chat highlights, screen chrome, toasts, headings. Also the value
     * the themeable {@link MarkerHue#AMBER} suspect-chunk default opens on.
     */
    public static final int AMBER = 0xFFB84C;

    /** Brightened amber for hover and focus. */
    public static final int AMBER_HOVER = 0xFFD680;

    /** Primary text on a dark surface, fixed for contrast against the dark HUD panel tint. */
    public static final int IVORY = 0xF0EAD6;

    /** Pure white, matching vanilla button-label text, for active-on-widget marks. */
    public static final int WHITE = 0xFFFFFF;

    /** Secondary text, subtitle, and inactive chrome. */
    public static final int GRAY = 0xAAAAAA;

    /** Destructive and error semantic; the recoverable state uses {@link #AMBER}, not this. */
    public static final int RUST = 0xD04040;

    /**
     * Info, link, and completion semantic. Also the value the themeable {@link MarkerHue#TEAL} covered-chunk default
     * opens on.
     */
    public static final int TEAL = 0x5BC0BE;

    /** Brightened teal for hover and focus, the {@link #AMBER_HOVER} sibling. */
    public static final int TEAL_HOVER = 0x92D5D4;

    /** The live-capture HUD glyph, a record dot. Its own role, kept distinct from the {@link #RUST} error red. */
    public static final int RECORDING_RED = 0xE53935;

    /** The finalizing HUD glyph, a neutral in-progress gray distinct from the {@link #GRAY} inactive-text tone. */
    public static final int SAVING_GRAY = 0xC8CDD2;

    /** The HUD panel and bar-track tint, a dark slate; the HUD draws it under a configurable opacity. */
    public static final int PANEL = 0x14161C;

    private BrandColors() {}

    /** The RGB token {@code rgb} forced fully opaque, supplying the {@code 0xFF} alpha the tokens above omit. */
    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }
}
