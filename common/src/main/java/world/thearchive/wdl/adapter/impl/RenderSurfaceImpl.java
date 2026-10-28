// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.class_372;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RenderSurface;

public final class RenderSurfaceImpl implements RenderSurface {
    private final @Nullable Screen screen;

    /** For a draw which never renders a tooltip. */
    public RenderSurfaceImpl() {
        this(null);
    }

    /** For a screen draw, whose tooltips this band renders through the screen itself. */
    public RenderSurfaceImpl(@Nullable Screen screen) {
        this.screen = screen;
    }

    @Override
    public void text(Font font, String text, int x, int y, int color) {
        font.drawShadow(text, x, y, color);
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(text, x, y, color);
        } else {
            font.draw(text, x, y, color);
        }
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color) {
        font.drawShadow(text.getString(), x, y, color);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(text.getString(), x, y, color);
        } else {
            font.draw(text.getString(), x, y, color);
        }
    }

    @Override
    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        class_372.method_988(minX, minY, maxX, maxY, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        class_372.method_988(x, y, x + width, y + 1, color);
        class_372.method_988(x, y + height - 1, x + width, y + height, color);
        class_372.method_988(x, y + 1, x + 1, y + height - 1, color);
        class_372.method_988(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        Minecraft.getInstance().getTextureManager()
                .bind(new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"));
        GlStateManager.method_9825((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        class_372.method_6675(x, y, 0.0F, 0.0F, width, height, width, height, 10, 10);
        GlStateManager.method_9825(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(ResourceLocation icon, int x, int y, int size) {
        Minecraft.getInstance().getTextureManager().bind(icon);
        // A selected list row leaves the GL color at black (the highlight sets it and never resets it), and the blit
        // does not set its own, so without this reset the selected row's icon multiplies to black.
        GlStateManager.method_9825(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.method_9843();
        class_372.method_6674(x, y, 0.0F, 0.0F, size, size, size, size);
        GlStateManager.method_9842();
    }

    @Override
    public void tooltip(Font font, Component content, int wrapWidth, int mouseX, int mouseY) {
        requireScreen().renderTooltip(font.split(content.getString(), wrapWidth), mouseX, mouseY);
    }

    @Override
    public void tooltip(Font font, List<Component> lines, int mouseX, int mouseY) {
        List<String> flattened = new ArrayList<>(lines.size());
        for (Component line : lines) {
            flattened.add(line.getString());
        }
        requireScreen().renderTooltip(flattened, mouseX, mouseY);
    }

    private Screen requireScreen() {
        if (screen == null) {
            throw new IllegalStateException("a tooltip below 1.20 needs the owning screen; use the screen constructor");
        }
        return screen;
    }

    @Override
    public int guiWidth() {
        return Minecraft.getInstance().window.getGuiScaledWidth();
    }

    @Override
    public int guiHeight() {
        return Minecraft.getInstance().window.getGuiScaledHeight();
    }
}
