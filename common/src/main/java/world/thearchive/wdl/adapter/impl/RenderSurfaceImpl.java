// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import world.thearchive.wdl.adapter.RenderSurface;

/** The {@link RenderSurface} plug for this branch, wrapping {@link GuiGraphics}. */
public final class RenderSurfaceImpl implements RenderSurface {
    private final GuiGraphics graphics;

    public RenderSurfaceImpl(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    @Override
    public void text(Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        graphics.fill(minX, minY, maxX, maxY, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        graphics.renderOutline(x, y, width, height, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        // This band has no GUI sprite atlas (blitSprite is 1.20.2 and later), so the sprite is drawn from its
        // texture file directly; the ARGB color is applied as a draw-color multiplier around the blit, since the
        // pre-1.21.2 blit likewise takes no tint argument. The sole wdl sprite is the 10 by 10 revert icon.
        graphics.setColor((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        graphics.blit(new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"), x, y, width, height,
                0.0F, 0.0F, 10, 10, 10, 10);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(FaviconTexture icon, int x, int y, int size) {
        graphics.blit(icon.textureLocation(), x, y, 0.0F, 0.0F, size, size, size, size);
    }

    @Override
    public void tooltip(Font font, Component content, int wrapWidth, int mouseX, int mouseY) {
        graphics.renderTooltip(font, font.split(content, wrapWidth), mouseX, mouseY);
    }

    @Override
    public void tooltip(Font font, List<Component> lines, int mouseX, int mouseY) {
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    @Override
    public int guiWidth() {
        return graphics.guiWidth();
    }

    @Override
    public int guiHeight() {
        return graphics.guiHeight();
    }
}
