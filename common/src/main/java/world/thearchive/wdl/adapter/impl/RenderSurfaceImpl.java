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

/**
 * The {@link RenderSurface} plug for this branch. At this band there is no {@code GuiGraphics} and GUI draws take no
 * {@code PoseStack}: draws go through the static {@link Gui} helpers and {@link FontRenderer}, and a tooltip renders
 * through the owning {@link GuiScreen}, the only object that draws one at this version. A HUD draw, which never shows a
 * tooltip, uses the screen-less constructor. The font draws take a {@code String}, so a {@link ITextComponent} is
 * flattened with {@code getString}.
 */
public final class RenderSurfaceImpl implements RenderSurface {
    private final @Nullable GuiScreen screen;

    /** For a HUD draw, which never renders a tooltip. */
    public RenderSurfaceImpl() {
        this(null);
    }

    /** For a screen draw, whose tooltips this band renders through the screen itself. */
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
        // This band has no GUI sprite atlas (blitSprite is 1.20.2 and later), so the sprite is drawn from its
        // texture file directly; the ARGB color is applied as a draw-color multiplier around the blit, since the
        // pre-1.20 blit likewise takes no tint argument. The sole wdl sprite is the 10 by 10 revert icon.
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
        // A selected list row leaves the shader color at black (the AbstractSelectionList highlight sets it and
        // never resets it), and the blit does not set its own, so without this reset the selected row's icon
        // multiplies to black. Vanilla's own world-selection list resets to white here for the same reason.
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

    /**
     * Draw a tooltip through Forge's own helper rather than {@code GuiScreen.drawHoveringText}. Every overload of that
     * method is protected at this band, where the higher bands ship two of them widened to public, and this surface is
     * not a screen subclass. Forge's helper is what the protected method delegates to anyway, and the width, height and
     * unlimited-wrap arguments below are the ones it passes.
     */
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
