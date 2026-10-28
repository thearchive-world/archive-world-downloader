// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import io.github.prospector.modmenu.api.ModMenuApi;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;

import world.thearchive.wdl.Wdl;

/**
 * ModMenu discovers this through the {@code modmenu} entrypoint only when it is installed, so the class never loads
 * without it; the ModMenu API is compile-only and never ships.
 */
public final class WdlFabricModMenu implements ModMenuApi {
    @Override
    public String getModId() {
        return "wdl";
    }

    @Override
    public Function<Screen, ? extends Screen> getConfigScreenFactory() {
        return Wdl::createSettingsScreen;
    }
}
