// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import world.thearchive.wdl.adapter.RenderSurface;

/** The {@link RenderSurface} plug for this branch, wrapping {@link GuiGraphics}. */
public final class RenderSurfaceImpl implements RenderSurface {
    private final GuiGraphics graphics;

    public RenderSurfaceImpl(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
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
    public int guiWidth() {
        return graphics.guiWidth();
    }

    @Override
    public int guiHeight() {
        return graphics.guiHeight();
    }
}
