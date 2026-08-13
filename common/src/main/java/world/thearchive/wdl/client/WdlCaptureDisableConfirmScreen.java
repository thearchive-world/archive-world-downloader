// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * The confirm for turning a download-harming capture toggle off. Unlike a plain {@link ConfirmScreen} it holds the exit
 * choices inert for a moment via vanilla's own {@code setDelay} (the death-screen delay pattern), so the
 * consequence-naming prompt cannot be clicked through on reflex. Esc does not dismiss it, so a cancel still lands on
 * the safe outcome.
 */
final class WdlCaptureDisableConfirmScreen extends ConfirmScreen {
    private static final int DANGER_DELAY_TICKS = 20;

    WdlCaptureDisableConfirmScreen(BooleanConsumer callback, Component title, Component message,
            Component disableButton, Component keepButton) {
        super(callback, title, message, disableButton, keepButton);
    }

    @Override
    protected void init() {
        super.init();
        setDelay(DANGER_DELAY_TICKS);
    }
}
