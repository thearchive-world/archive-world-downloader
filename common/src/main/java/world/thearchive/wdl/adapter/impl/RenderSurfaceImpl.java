// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * The {@link RenderSurface} plug for this branch. Below 1.20 there is no {@code GuiGraphics}: draws go through the
 * static {@link GuiComponent} helpers and {@link Font} against a {@link PoseStack}, and a tooltip renders through the
 * owning {@link Screen}, the only object that draws one at this version. A HUD draw, which never shows a tooltip, uses
 * the screen-less constructor.
 */
public final class RenderSurfaceImpl implements RenderSurface {
    private final PoseStack poseStack;
    private final @Nullable Screen screen;

    /** For a HUD draw, which never renders a tooltip. */
    public RenderSurfaceImpl(PoseStack poseStack) {
        this(poseStack, null);
    }

    /** For a screen draw, whose tooltips this band renders through the screen itself. */
    public RenderSurfaceImpl(PoseStack poseStack, @Nullable Screen screen) {
        this.poseStack = poseStack;
        this.screen = screen;
    }

    @Override
    public void text(Font font, String text, int x, int y, int color) {
        font.drawShadow(poseStack, text, x, y, color);
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(poseStack, text, x, y, color);
        } else {
            font.draw(poseStack, text, x, y, color);
        }
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color) {
        font.drawShadow(poseStack, text, x, y, color);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(poseStack, text, x, y, color);
        } else {
            font.draw(poseStack, text, x, y, color);
        }
    }

    @Override
    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        GuiComponent.fill(poseStack, minX, minY, maxX, maxY, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        GuiComponent.fill(poseStack, x, y, x + width, y + 1, color);
        GuiComponent.fill(poseStack, x, y + height - 1, x + width, y + height, color);
        GuiComponent.fill(poseStack, x, y + 1, x + 1, y + height - 1, color);
        GuiComponent.fill(poseStack, x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        // This band has no GUI sprite atlas (blitSprite is 1.20.2 and later), so the sprite is drawn from its
        // texture file directly; the ARGB color is applied as a draw-color multiplier around the blit, since the
        // pre-1.20 blit likewise takes no tint argument. The sole wdl sprite is the 10 by 10 revert icon.
        RenderSystem.setShaderTexture(0, new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"));
        RenderSystem.setShaderColor((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        GuiComponent.blit(poseStack, x, y, width, height, 0.0F, 0.0F, 10, 10, 10, 10);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(ResourceLocation icon, int x, int y, int size) {
        RenderSystem.setShaderTexture(0, icon);
        // A selected list row leaves the shader color at black (the 1.17.1 AbstractSelectionList highlight sets it and
        // never resets it), and GuiComponent.blit does not set its own, so without this reset the selected row's icon
        // multiplies to black. Vanilla's own world-selection list resets to white here for the same reason.
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        GuiComponent.blit(poseStack, x, y, 0.0F, 0.0F, size, size, size, size);
        RenderSystem.disableBlend();
    }

    @Override
    public void tooltip(Font font, Component content, int wrapWidth, int mouseX, int mouseY) {
        requireScreen().renderTooltip(poseStack, font.split(content, wrapWidth), mouseX, mouseY);
    }

    @Override
    public void tooltip(Font font, List<Component> lines, int mouseX, int mouseY) {
        requireScreen().renderComponentTooltip(poseStack, lines, mouseX, mouseY);
    }

    private Screen requireScreen() {
        if (screen == null) {
            throw new IllegalStateException("a tooltip below 1.20 needs the owning screen; use the screen constructor");
        }
        return screen;
    }

    @Override
    public int guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    @Override
    public int guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
