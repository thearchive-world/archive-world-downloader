// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.util.text.ITextComponent;

/**
 * Unlike a plain {@link GuiYesNo} it holds the exit choices inert for a moment, so the consequence-naming prompt cannot
 * be clicked through on reflex.
 */
final class WdlCaptureDisableConfirmScreen extends GuiYesNo {
    private static final int DANGER_DELAY_TICKS = 20;

    WdlCaptureDisableConfirmScreen(Consumer<Boolean> callback, ITextComponent title, ITextComponent message,
            ITextComponent disableButton, ITextComponent keepButton) {
        // This band's GuiYesNo draws the title in a fixed white, so the amber name in the title rides the legacy
        // section codes getFormattedText emits (getUnformattedText would strip them).
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
        // The loader's HUD pass runs before any screen, so a WDL screen draws the tray from its own render and the HUD
        // hook stands down while it is open.
        WdlToastOverlay.render();
    }
}
