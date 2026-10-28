// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.adapter.RenderSurface;

public final class RenderSurfaceImpl implements RenderSurface {
    private final @Nullable GuiScreen screen;

    /** For a draw which never renders a tooltip. */
    public RenderSurfaceImpl() {
        this(null);
    }

    /** For a screen draw. */
    public RenderSurfaceImpl(@Nullable GuiScreen screen) {
        this.screen = screen;
    }

    @Override
    public void text(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
    }

    @Override
    public void text(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(text, x, y, color);
        } else {
            font.drawString(text, x, y, color);
        }
    }

    @Override
    public void text(FontRenderer font, ITextComponent text, int x, int y, int color) {
        font.drawStringWithShadow(text.getUnformattedText(), x, y, color);
    }

    @Override
    public void text(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(text.getUnformattedText(), x, y, color);
        } else {
            font.drawString(text.getUnformattedText(), x, y, color);
        }
    }

    @Override
    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        Gui.drawRect(minX, minY, maxX, maxY, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        Gui.drawRect(x, y, x + width, y + 1, color);
        Gui.drawRect(x, y + height - 1, x + width, y + height, color);
        Gui.drawRect(x, y + 1, x + 1, y + height - 1, color);
        Gui.drawRect(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public void blitSprite(String sprite, int x, int y, int width, int height, int color) {
        Minecraft.getMinecraft().getTextureManager()
                .bindTexture(new ResourceLocation("wdl", "textures/gui/sprites/" + sprite + ".png"));
        GlStateManager.color((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        Gui.drawScaledCustomSizeModalRect(x, y, 0.0F, 0.0F, width, height, width, height, 10, 10);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void blitFavicon(ResourceLocation icon, int x, int y, int size) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(icon);
        // A selected list row leaves the GL color at black (the GuiSlot highlight sets it and never resets it), and the
        // blit does not set its own, so without this reset the selected row's icon multiplies to black.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 0.0F, size, size, size, size);
        GlStateManager.disableBlend();
    }

    @Override
    public void tooltip(FontRenderer font, ITextComponent content, int wrapWidth, int mouseX, int mouseY) {
        drawTooltip(font, font.listFormattedStringToWidth(content.getUnformattedText(), wrapWidth), mouseX, mouseY);
    }

    @Override
    public void tooltip(FontRenderer font, List<ITextComponent> lines, int mouseX, int mouseY) {
        List<String> flattened = new ArrayList<>(lines.size());
        for (ITextComponent line : lines) {
            flattened.add(line.getUnformattedText());
        }
        drawTooltip(font, flattened, mouseX, mouseY);
    }

    private void drawTooltip(FontRenderer font, List<String> lines, int mouseX, int mouseY) {
        GuiScreen screen = requireScreen();
        GuiUtils.drawHoveringText(lines, mouseX, mouseY, screen.width, screen.height, -1, font);
    }

    private GuiScreen requireScreen() {
        if (screen == null) {
            throw new IllegalStateException("a tooltip below 1.20 needs the owning screen; use the screen constructor");
        }
        return screen;
    }

    @Override
    public int guiWidth() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();
    }

    @Override
    public int guiHeight() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
    }
}
