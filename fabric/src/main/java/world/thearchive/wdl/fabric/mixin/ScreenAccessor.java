// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric.mixin;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the vanilla {@code buttons} and {@code children} lists, both declared on {@link Screen} and not on
 * PauseScreen, so {@link PauseScreenMixin} adds its row straight to them. This is two escapes at once: the mixin
 * processor will not resolve an inherited shadow against the PauseScreen subclass target, and going through the vanilla
 * addButton would let a coexisting mod that overrides it to reflow the pause menu (ModMenu does) shift the row out of
 * place, so the row is added to the lists directly instead.
 */
@Mixin(Screen.class)
interface ScreenAccessor {
    @Accessor("buttons")
    List<AbstractWidget> wdlButtons();

    @Accessor("children")
    List<GuiEventListener> wdlChildren();
}
