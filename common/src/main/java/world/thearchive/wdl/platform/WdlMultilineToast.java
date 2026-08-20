// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;

/**
 * A toast that renders a title over several wrapped body lines. The 1.15.2 SystemToast draws its body as one line and
 * its ToastComponent slot is a fixed 32 pixels, so a body carrying a newline (the download-complete chunk count then
 * the save name) renders the newline as a tofu glyph and overruns the frame. This wraps the body to the frame width and
 * stitches the 160 by 32 frame sprite taller to hold the extra lines. The higher bands use SystemToast.multiline,
 * absent here.
 */
final class WdlMultilineToast implements Toast {
    private static final int INNER_WIDTH = 160 - 18 - 8;
    private static final int LINE_STEP = 12;
    private static final long DISPLAY_MS = 5000L;

    private final String title;
    private final List<String> lines;
    private final int bodyColor;
    private final int frameHeight;

    WdlMultilineToast(Font font, String title, String body, int bodyColor) {
        this.title = title;
        this.lines = font.split(body, INNER_WIDTH);
        this.bodyColor = bodyColor;
        this.frameHeight = 32 + Math.max(0, this.lines.size() - 1) * LINE_STEP;
    }

    @Override
    public Toast.Visibility render(ToastComponent toastComponent, long elapsed) {
        Minecraft mc = toastComponent.getMinecraft();
        mc.getTextureManager().bind(TEXTURE);
        RenderSystem.color3f(1.0F, 1.0F, 1.0F);
        // Keep the 16-pixel top and bottom caps of the frame sprite, which carry the borders, and tile a one-pixel
        // interior slice between them; at frameHeight 32 the two caps meet and reproduce the vanilla frame exactly.
        toastComponent.blit(0, 0, 0, 64, 160, 16);
        for (int y = 16; y < frameHeight - 16; y++) {
            toastComponent.blit(0, y, 0, 78, 160, 1);
        }
        toastComponent.blit(0, frameHeight - 16, 0, 80, 160, 16);

        Font font = mc.font;
        font.draw(this.title, 18.0F, 7.0F, -256);
        for (int i = 0; i < this.lines.size(); i++) {
            font.draw(this.lines.get(i), 18.0F, 18.0F + i * LINE_STEP, this.bodyColor);
        }
        return elapsed < DISPLAY_MS ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
    }
}
