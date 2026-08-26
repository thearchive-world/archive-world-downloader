// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter;

import java.util.List;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;

/**
 * The client draw seam: the band-stable subset of the vanilla GUI surface that shared client code draws through, so
 * {@code client/} names no vanilla draw-surface type. One implementation per band in {@code adapter/impl} wraps that
 * band's concrete surface, which is the type that moves across the version bands. The interface may name band-stable
 * vanilla types ({@link FontRenderer}, {@link ITextComponent}) on the same license {@code adapter/} already has, but
 * never the draw surface itself. A sprite is addressed by a mod-owned key the plug resolves rather than a vanilla id;
 * below 1.20, where there is no {@code FaviconTexture} handle to pass, a world favicon is addressed by its
 * already-registered texture location.
 */
public interface RenderSurface {
    /** Draw a string with a drop shadow. */
    void text(FontRenderer font, String text, int x, int y, int color);

    /** Draw a string, choosing whether to drop a shadow. */
    void text(FontRenderer font, String text, int x, int y, int color, boolean shadow);

    /** Draw a component with a drop shadow. */
    void text(FontRenderer font, ITextComponent text, int x, int y, int color);

    /** Draw a component, choosing whether to drop a shadow. */
    void text(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow);

    /** Fill an axis-aligned rectangle with an ARGB color. */
    void fill(int minX, int minY, int maxX, int maxY, int color);

    /** Draw a one-pixel outline around an axis-aligned rectangle in an ARGB color. */
    void outline(int x, int y, int width, int height, int color);

    /** Draw a mod-owned atlas sprite tinted by an ARGB color, named by its {@code wdl}-namespace path. */
    void blitSprite(String sprite, int x, int y, int width, int height, int color);

    /** Draw a square world favicon from its registered texture location. */
    void blitFavicon(ResourceLocation icon, int x, int y, int size);

    /** Show a tooltip that wraps {@code content} at {@code wrapWidth}, anchored at the cursor. */
    void tooltip(FontRenderer font, ITextComponent content, int wrapWidth, int mouseX, int mouseY);

    /** Show a tooltip of discrete lines, one per component, anchored at the cursor. */
    void tooltip(FontRenderer font, List<ITextComponent> lines, int mouseX, int mouseY);

    /** The framebuffer width in GUI-scaled pixels. */
    int guiWidth();

    /** The framebuffer height in GUI-scaled pixels. */
    int guiHeight();
}
