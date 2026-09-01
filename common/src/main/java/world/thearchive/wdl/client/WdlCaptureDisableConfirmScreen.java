// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.util.text.ITextComponent;

/**
 * The confirm for turning a download-harming capture toggle off. Unlike a plain {@link GuiYesNo} it holds the exit
 * choices inert for a moment via vanilla's own {@code setButtonDelay} (the death-screen delay pattern), so the
 * consequence-naming prompt cannot be clicked through on reflex. Esc does not dismiss it, so a cancel still lands on
 * the safe outcome.
 */
final class WdlCaptureDisableConfirmScreen extends GuiYesNo {
    private static final int DANGER_DELAY_TICKS = 20;

    WdlCaptureDisableConfirmScreen(Consumer<Boolean> callback, ITextComponent title, ITextComponent message,
            ITextComponent disableButton, ITextComponent keepButton) {
        // This band's GuiYesNo takes a two-argument confirm callback and plain-string title, message, and
        // button labels, plus a trailing dialog id; it draws the title in a fixed white, so the amber option name in
        // the title rides the legacy section codes getFormattedText emits (getUnformattedText would strip them).
        super((confirmed, dialogId) -> callback.accept(confirmed), title.getFormattedText(),
                message.getUnformattedText(),
                disableButton.getUnformattedText(), keepButton.getUnformattedText(), 0);
    }

    @Override
    public void initGui() {
        super.initGui();
        setButtonDelay(DANGER_DELAY_TICKS);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        // The notification tray, which this band draws itself. The loader's HUD pass runs before any screen
        // and would be painted over by this one, so a WDL screen draws the tray from its own render and the
        // HUD hook stands down while a screen is open; exactly one path draws it per frame.
        WdlToastOverlay.render();
    }
}
