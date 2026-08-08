// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.gametest;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlDownloadsScreen;

/**
 * Drives the download screen from a client gametest: opens the real screen through the {@code /wdl downloads} client
 * command (so the production wiring builds it), waits for a row's restore chip to become clickable, and clicks a
 * hand-drawn hit box by translating its scaled-screen rectangle into a physical cursor position. The screen exposes the
 * chip's live rectangle, so no pixel layout is recomputed here; the row-body helper offsets from that same rectangle
 * onto the row's first line, which carries no interactive element, to select the row.
 */
@SuppressWarnings("UnstableApiUsage")
final class ScreenDriver {
    private static final int CHIP_WAIT_TICKS = 120;

    private ScreenDriver() {}

    /** Open the download screen through the client command and wait until it is the active screen. */
    static void openDownloads(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.getConnection() != null) {
                client.getConnection().sendCommand("wdl downloads");
            }
        });
        context.waitForScreen(WdlDownloadsScreen.class);
    }

    /** The named row's live restore-chip rectangle on the active download screen, or null when it is absent. */
    static @Nullable ScreenRectangle chipBox(ClientGameTestContext context, String folderName) {
        return context.computeOnClient(client -> client.gui.screen() instanceof WdlDownloadsScreen screen
                ? screen.restoreChipBox(folderName)
                : null);
    }

    /**
     * Wait for the named row's restore chip to appear (the availability cache fills off-thread and the chip box lands
     * on the next render), returning its rectangle. Fails when it never appears within the wait budget.
     */
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

    /** Click the center of a scaled-screen rectangle by positioning the physical cursor over it and pressing. */
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
