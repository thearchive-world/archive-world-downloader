// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.renderer.RenderType;
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
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        graphics.blitSprite(RenderType::guiTextured, ResourceLocation.fromNamespaceAndPath("wdl", sprite),
                x, y, width, height, color);
    }

    @Override
    public void blitFavicon(FaviconTexture icon, int x, int y, int size) {
        graphics.blit(RenderType::guiTextured, icon.textureLocation(), x, y, 0.0F, 0.0F,
                size, size, size, size);
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
