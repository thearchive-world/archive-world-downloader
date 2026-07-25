// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The HUD-overlay side of the config: the master on/off toggle, anchor, offset, the compact/detailed default, the
 * peek-key mode, the optional panel and its opacity, and the done-linger hold. A nested value object composed onto
 * {@link WdlConfig}, constructed from a schema read via {@link #from(ConfigValues)}, so the parse, clamping, and
 * defaults live in {@link ConfigSchema} rather than here. MC-free and headless-testable; the keybind itself is a loader
 * concern, not a stored key.
 */
public final class HudConfig {
    private final boolean showHud;
    private final HudAnchor anchor;
    private final int offsetX;
    private final int offsetY;
    private final boolean detailed;
    private final HudPeekMode peekMode;
    private final boolean background;
    private final int panelOpacity;
    private final int doneLingerSeconds;

    HudConfig(boolean showHud, HudAnchor anchor, int offsetX, int offsetY, boolean detailed, HudPeekMode peekMode,
            boolean background, int panelOpacity, int doneLingerSeconds) {
        this.showHud = showHud;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.detailed = detailed;
        this.peekMode = peekMode;
        this.background = background;
        this.panelOpacity = panelOpacity;
        this.doneLingerSeconds = doneLingerSeconds;
    }

    /** Master: whether the rendered overlay is drawn at all (default on); when off, the chat line stands. */
    public boolean showHud() {
        return showHud;
    }

    /** Which screen corner or edge the overlay anchors to (default a top corner). */
    public HudAnchor anchor() {
        return anchor;
    }

    /** The horizontal nudge from the anchor in pixels, clamped to the screen at draw time. */
    public int offsetX() {
        return offsetX;
    }

    /** The vertical nudge from the anchor in pixels, clamped to the screen at draw time. */
    public int offsetY() {
        return offsetY;
    }

    /** Whether the detailed layout is the default (the peek key overrides this live). */
    public boolean detailed() {
        return detailed;
    }

    /** Whether the peek key holds-to-peek or toggles the detailed layout. */
    public HudPeekMode peekMode() {
        return peekMode;
    }

    /** Whether a panel is drawn behind the overlay (default off, so the text is drawn with a drop shadow). */
    public boolean background() {
        return background;
    }

    /** The panel alpha as a percent (0 to 100) when the panel is shown. */
    public int panelOpacity() {
        return panelOpacity;
    }

    /** How long (seconds) the done frame holds at full opacity before it fades out. */
    public int doneLingerSeconds() {
        return doneLingerSeconds;
    }

    /** The HUD fields drawn from a completed schema read. */
    static HudConfig from(ConfigValues values) {
        return new HudConfig(
                values.booleanValue("showHud"),
                values.enumValue("hudAnchor", HudAnchor.class),
                values.integer("hudOffsetX"),
                values.integer("hudOffsetY"),
                values.booleanValue("hudDetailed"),
                values.enumValue("hudPeekMode", HudPeekMode.class),
                values.booleanValue("hudBackground"),
                values.integer("hudPanelOpacity"),
                values.integer("hudDoneLingerSeconds"));
    }
}
