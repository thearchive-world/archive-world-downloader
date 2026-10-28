// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * Unlike a plain {@link ConfirmScreen} it focuses the safe keep choice as the default and holds the dangerous disable
 * choice inert for a moment, so the consequence-naming prompt cannot be clicked through on reflex.
 */
final class WdlCaptureDisableConfirmScreen extends ConfirmScreen {
    private static final int DANGER_DELAY_TICKS = 20;

    private int dangerDelay = DANGER_DELAY_TICKS;

    WdlCaptureDisableConfirmScreen(BooleanConsumer callback, Component title, Component message,
            Component disableButton, Component keepButton) {
        super(callback, title, message, disableButton, keepButton);
    }

    @Override
    protected void init() {
        super.init();
        if (this.noButton != null) {
            setInitialFocus(this.noButton);
        }
        if (this.yesButton != null) {
            this.yesButton.active = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.dangerDelay > 0 && --this.dangerDelay == 0 && this.yesButton != null) {
            this.yesButton.active = true;
        }
    }
}
