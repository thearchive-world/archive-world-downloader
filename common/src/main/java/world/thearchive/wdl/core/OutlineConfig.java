// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

/**
 * The unsaved-container outline side of the config: the on/off master, the camera-centered render distance, the two
 * state hues, and the rim line-width scale. A nested value object composed onto {@link WdlConfig}, constructed from a
 * schema read via {@link #from(ConfigValues)}, so the parse, clamping, and defaults live in {@link ConfigSchema} rather
 * than here. The hues store a {@link MarkerHue} constant by name, the same way the HUD block stores its enums. MC-free
 * and headless-testable.
 */
public final class OutlineConfig {
    private final boolean renderUnsavedOutline;
    private final int outlineDistance;
    private final MarkerHue unscannedColor;
    private final MarkerHue recoveredColor;
    private final float lineWidthScale;
    private final boolean debugTiming;

    OutlineConfig(boolean renderUnsavedOutline, int outlineDistance, MarkerHue unscannedColor,
            MarkerHue recoveredColor, float lineWidthScale, boolean debugTiming) {
        this.renderUnsavedOutline = renderUnsavedOutline;
        this.outlineDistance = outlineDistance;
        this.unscannedColor = unscannedColor;
        this.recoveredColor = recoveredColor;
        this.lineWidthScale = lineWidthScale;
        this.debugTiming = debugTiming;
    }

    /** Whether the in-world unsaved-container outline is drawn at all (the master toggle). */
    public boolean renderUnsavedOutline() {
        return renderUnsavedOutline;
    }

    /** The camera-centered distance, in blocks, beyond which a container is not outlined. */
    public int outlineDistance() {
        return outlineDistance;
    }

    /** The hue of a still-unsaved container's rim (the actionable to-do color). */
    public MarkerHue unscannedColor() {
        return unscannedColor;
    }

    /** The hue of a prior-session-recovered container's rim (the informational color). */
    public MarkerHue recoveredColor() {
        return recoveredColor;
    }

    /** The rim line thickness as a multiple of the band's default outline width; 1.0 is the vanilla width. */
    public float lineWidthScale() {
        return lineWidthScale;
    }

    /** Diagnostic (default off): log the per-window outline tick and render-thread cost. */
    public boolean debugTiming() {
        return debugTiming;
    }

    /** The outline fields drawn from a completed schema read. */
    static OutlineConfig from(ConfigValues values) {
        return new OutlineConfig(
                values.booleanValue("renderUnsavedOutline"),
                values.integer("outlineDistance"),
                values.enumValue("unscannedColor", MarkerHue.class),
                values.enumValue("recoveredColor", MarkerHue.class),
                values.floatValue("outlineLineWidthScale"),
                values.booleanValue("outlineDebugTiming"));
    }
}
