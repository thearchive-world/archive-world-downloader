// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * Unlike a plain {@link ConfirmScreen} it holds the exit choices inert for a moment, so the consequence-naming prompt
 * cannot be clicked through on reflex.
 */
final class WdlCaptureDisableConfirmScreen extends ConfirmScreen {
    private static final int DANGER_DELAY_TICKS = 20;

    WdlCaptureDisableConfirmScreen(BooleanConsumer callback, Component title, Component message,
            Component disableButton, Component keepButton) {
        // This band's ConfirmScreen draws the title in a fixed white, so the amber name in the title rides the legacy
        // section codes getColoredString emits (getString would strip them).
        super((confirmed, dialogId) -> callback.accept(confirmed), title.getColoredString(), message.getString(),
                disableButton.getString(), keepButton.getString(), 0);
    }

    @Override
    protected void init() {
        super.init();
        setDelay(DANGER_DELAY_TICKS);
    }
}
