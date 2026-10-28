// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

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

    public boolean renderUnsavedOutline() {
        return renderUnsavedOutline;
    }

    public int outlineDistance() {
        return outlineDistance;
    }

    /** The hue of a still-unsaved container's rim. */
    public MarkerHue unscannedColor() {
        return unscannedColor;
    }

    /** The hue of a prior-session-recovered container's rim. */
    public MarkerHue recoveredColor() {
        return recoveredColor;
    }

    public float lineWidthScale() {
        return lineWidthScale;
    }

    public boolean debugTiming() {
        return debugTiming;
    }

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
