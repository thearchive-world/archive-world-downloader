// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * The client draw seam: the band-stable subset of the vanilla GUI surface that shared client code draws through, so
 * {@code client/} names no vanilla draw-surface type. One implementation per band in {@code adapter/impl} wraps that
 * band's concrete surface, which is the type that moves across the version bands. The interface may name band-stable
 * vanilla types ({@link Font}, {@link Component}) on the same license {@code adapter/} already has, but never the draw
 * surface itself.
 */
public interface RenderSurface {
    /** Draw a string payload at the given position, mirroring the band's text primitive. */
    void text(Font font, String text, int x, int y, int color, boolean shadow);

    /** Draw a component payload at the given position. */
    void text(Font font, Component text, int x, int y, int color, boolean shadow);

    /** Fill an axis-aligned rectangle with an ARGB color. */
    void fill(int minX, int minY, int maxX, int maxY, int color);

    /** The framebuffer width in GUI-scaled pixels. */
    int guiWidth();

    /** The framebuffer height in GUI-scaled pixels. */
    int guiHeight();
}
