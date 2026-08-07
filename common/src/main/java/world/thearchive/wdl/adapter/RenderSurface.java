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
 * surface itself and never the resource-id type, which is why a sprite is addressed by a mod-owned key that the plug
 * resolves rather than by a vanilla id.
 */
public interface RenderSurface {
    /** Draw a string with a drop shadow. */
    void text(Font font, String text, int x, int y, int color);

    /** Draw a string, choosing whether to drop a shadow. */
    void text(Font font, String text, int x, int y, int color, boolean shadow);

    /** Draw a component with a drop shadow. */
    void text(Font font, Component text, int x, int y, int color);

    /** Draw a component, choosing whether to drop a shadow. */
    void text(Font font, Component text, int x, int y, int color, boolean shadow);

    /** Fill an axis-aligned rectangle with an ARGB color. */
    void fill(int minX, int minY, int maxX, int maxY, int color);

    /** Draw a mod-owned atlas sprite tinted by an ARGB color, named by its {@code wdl}-namespace path. */
    void blitSprite(String sprite, int x, int y, int width, int height, int color);

    /** The framebuffer width in GUI-scaled pixels. */
    int guiWidth();

    /** The framebuffer height in GUI-scaled pixels. */
    int guiHeight();
}
