// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlDownloadsScreen;

@SuppressWarnings("UnstableApiUsage")
final class ScreenDriver {
    private static final int CHIP_WAIT_TICKS = 120;

    private ScreenDriver() {}

    static void openDownloads(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.getConnection() != null) {
                client.getConnection().sendCommand("wdl downloads");
            }
        });
        context.waitForScreen(WdlDownloadsScreen.class);
    }

    static @Nullable ScreenRectangle chipBox(ClientGameTestContext context, String folderName) {
        return context.computeOnClient(client -> client.screen instanceof WdlDownloadsScreen screen
                ? screen.restoreChipBox(folderName)
                : null);
    }

    static ScreenRectangle waitForChip(ClientGameTestContext context, String folderName) {
        for (int tick = 0; tick < CHIP_WAIT_TICKS; tick++) {
            ScreenRectangle box = chipBox(context, folderName);
            if (box != null) {
                return box;
            }
            context.waitTick();
        }
        throw new AssertionError("the restore chip for '" + folderName + "' never became clickable");
    }

    static void clickRectangle(ClientGameTestContext context, ScreenRectangle rectangle) {
        clickScaled(context, rectangle.left() + rectangle.width() / 2.0, rectangle.top() + rectangle.height() / 2.0);
    }

    /** Select a row by clicking its first line, offset above the chip rectangle where no chip is drawn. */
    static void clickRowBody(ClientGameTestContext context, ScreenRectangle chip) {
        clickScaled(context, chip.left(), chip.top() - 8.0);
    }

    private static void clickScaled(ClientGameTestContext context, double scaledX, double scaledY) {
        double[] physical = context.computeOnClient(client -> {
            Window window = client.getWindow();
            double rawX = scaledX * window.getScreenWidth() / window.getGuiScaledWidth();
            double rawY = scaledY * window.getScreenHeight() / window.getGuiScaledHeight();
            return new double[] { rawX, rawY };
        });
        context.getInput().setCursorPos(physical[0], physical[1]);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
