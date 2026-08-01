// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChecker;
import net.minecraft.client.gui.screens.Screen;

import world.thearchive.wdl.Wdl;

/**
 * ModMenu integration (optional soft-dependency): the mod-list config button opens the in-mod settings screen with the
 * mods screen as its back target. ModMenu discovers this through the {@code modmenu} entrypoint only when it is
 * installed, so the class never loads without it; the ModMenu API is compile-only and never ships.
 */
public final class WdlFabricModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        ConfigScreenFactory<Screen> factory = Wdl::createSettingsScreen;
        return factory;
    }

    @Override
    public UpdateChecker getUpdateChecker() {
        return new WdlModMenuUpdateChecker();
    }
}
