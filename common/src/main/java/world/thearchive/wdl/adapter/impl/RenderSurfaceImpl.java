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

public final class RenderSurfaceImpl implements RenderSurface {
    private final PoseStack poseStack;
    private final @Nullable Screen screen;

    /** For a draw which never renders a tooltip. */
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
        RenderSystem.setShaderTexture(0, new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"));
        RenderSystem.setShaderColor((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        GuiComponent.blit(poseStack, x, y, width, height, 0.0F, 0.0F, 10, 10, 10, 10);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(ResourceLocation icon, int x, int y, int size) {
        RenderSystem.setShaderTexture(0, icon);
        // A selected list row leaves the shader color at black (the 1.18.2 AbstractSelectionList highlight sets it and
        // never resets it), and GuiComponent.blit does not set its own, so without this reset the selected row's icon
        // multiplies to black.
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
