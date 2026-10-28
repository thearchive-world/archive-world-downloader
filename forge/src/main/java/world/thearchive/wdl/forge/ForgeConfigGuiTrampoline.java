// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import net.minecraft.client.gui.GuiScreen;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;

/**
 * The screen class {@link ForgeConfigGuiFactory} answers with. The mods list opens that answer by calling a public
 * {@code (GuiScreen)} constructor on it, and the shared settings screen is final with a wider constructor, so this
 * band-local screen carries that constructor shape.
 */
public final class ForgeConfigGuiTrampoline extends GuiScreen {
    private final @Nullable GuiScreen parent;

    public ForgeConfigGuiTrampoline(@Nullable GuiScreen parent) {
        this.parent = parent;
    }

    // The handover cannot move to the constructor: mc is still null there.
    @Override
    public void initGui() {
        mc.displayGuiScreen(Wdl.createSettingsScreen(parent));
    }
}
