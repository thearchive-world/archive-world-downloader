// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("wdl", sprite),
                x, y, width, height, color);
    }

    @Override
    public void blitFavicon(FaviconTexture icon, int x, int y, int size) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon.textureLocation(), x, y, 0.0F, 0.0F,
                size, size, size, size);
    }

    @Override
    public void tooltip(Font font, Component content, int wrapWidth, int mouseX, int mouseY) {
        graphics.setTooltipForNextFrame(font, font.split(content, wrapWidth), mouseX, mouseY);
    }

    @Override
    public void tooltip(Font font, List<Component> lines, int mouseX, int mouseY) {
        graphics.setTooltipForNextFrame(font, lines, Optional.empty(), mouseX, mouseY);
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
