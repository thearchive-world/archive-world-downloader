// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import java.util.Collections;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import org.jspecify.annotations.Nullable;

import world.thearchive.wdl.Wdl;

/**
 * The mods-list config-screen hook at this band, the analog of the {@code ExtensionPoint.CONFIGGUIFACTORY}
 * registration the 1.14.4-and-above bands make. FML resolves this class by name from the {@code guiFactory}
 * attribute on {@link WdlForge}'s {@code @Mod} annotation and instantiates it reflectively, so it stays public with
 * a no-arg constructor; the mods list greys its config button out unless {@link #hasConfigGui} returns true.
 *
 * <p>The interface carries two more members at this band that the 1.12.x line dropped, both deprecated there and
 * both nullable, and they are the older mechanism the config button superseded: a screen class FML would have
 * instantiated itself, and a handler for in-game runtime option categories. Neither is wanted when
 * {@link #hasConfigGui} answers true, so both return null, which is what FML's own no-config factories return.
 */
public final class ForgeConfigGuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        return Wdl.createSettingsScreen(parentScreen);
    }

    @Override
    public @Nullable Class<? extends GuiScreen> mainConfigGuiClass() {
        return null;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }

    @Override
    public @Nullable RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
