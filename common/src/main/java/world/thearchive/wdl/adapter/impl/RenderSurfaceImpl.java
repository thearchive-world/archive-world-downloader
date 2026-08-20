// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RenderSurface;

/**
 * The {@link RenderSurface} plug for this branch. At 1.15.2 there is no {@code GuiGraphics} and GUI draws take no
 * {@code PoseStack}: draws go through the static {@link GuiComponent} helpers and {@link Font}, and a tooltip renders
 * through the owning {@link Screen}, the only object that draws one at this version. A HUD draw, which never shows a
 * tooltip, uses the screen-less constructor. The font draws take a {@code String}, so a {@link Component} is flattened
 * with {@code getString}.
 */
public final class RenderSurfaceImpl implements RenderSurface {
    private final @Nullable Screen screen;

    /** For a HUD draw, which never renders a tooltip. */
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
        GuiComponent.fill(minX, minY, maxX, maxY, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        GuiComponent.fill(x, y, x + width, y + 1, color);
        GuiComponent.fill(x, y + height - 1, x + width, y + height, color);
        GuiComponent.fill(x, y + 1, x + 1, y + height - 1, color);
        GuiComponent.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        // This band has no GUI sprite atlas (blitSprite is 1.20.2 and later), so the sprite is drawn from its
        // texture file directly; the ARGB color is applied as a draw-color multiplier around the blit, since the
        // pre-1.20 blit likewise takes no tint argument. The sole wdl sprite is the 10 by 10 revert icon.
        Minecraft.getInstance().getTextureManager()
                .bind(new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"));
        GlStateManager.color4f((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        GuiComponent.blit(x, y, width, height, 0.0F, 0.0F, 10, 10, 10, 10);
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(ResourceLocation icon, int x, int y, int size) {
        Minecraft.getInstance().getTextureManager().bind(icon);
        // A selected list row leaves the shader color at black (the 1.15.2 AbstractSelectionList highlight sets it and
        // never resets it), and GuiComponent.blit does not set its own, so without this reset the selected row's icon
        // multiplies to black. Vanilla's own world-selection list resets to white here for the same reason.
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GuiComponent.blit(x, y, 0.0F, 0.0F, size, size, size, size);
        GlStateManager.disableBlend();
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
