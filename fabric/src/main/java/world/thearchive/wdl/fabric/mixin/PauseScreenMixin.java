// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.mixin;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import world.thearchive.wdl.fabric.FabricPlatformBridge;

/**
 * Adds the wdl pause-menu row to the vanilla pause screen at the tail of {@code init()}, after every vanilla button.
 * fabric-api 0.28.5+1.15 ships no fabric-screen-api-v1 (ScreenEvents), so the row has no non-mixin screen hook. The row
 * goes straight into the Screen buttons and children lists through {@link ScreenAccessor}, mirroring the vanilla
 * addButton but bypassing any pause-screen override of it, so buildPauseMenuRow's placement holds.
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void wdlAddPauseMenuButtons(CallbackInfo callbackInfo) {
        ScreenAccessor screen = (ScreenAccessor) (Object) this;
        List<AbstractWidget> buttons = screen.wdlButtons();
        List<GuiEventListener> children = screen.wdlChildren();
        for (AbstractWidget widget : FabricPlatformBridge.pauseMenuRow((PauseScreen) (Object) this, buttons)) {
            buttons.add(widget);
            children.add(widget);
        }
    }
}
