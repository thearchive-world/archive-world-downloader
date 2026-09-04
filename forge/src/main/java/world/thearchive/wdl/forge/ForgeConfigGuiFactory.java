// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import java.util.Collections;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import org.jspecify.annotations.Nullable;

/**
 * The mods-list config-screen hook at this band, the analog of the {@code ExtensionPoint.CONFIGGUIFACTORY}
 * registration the 1.14.4-and-above bands make. FML resolves this class by name from the {@code guiFactory}
 * attribute on {@link WdlForge}'s {@code @Mod} annotation and instantiates it reflectively, so it stays public with a
 * no-arg constructor. The mods list greys its config button out unless {@link #mainConfigGuiClass} answers non-null,
 * and opens the class it answers by calling a public {@code (GuiScreen)} constructor on it, which is why that answer
 * is {@link ForgeConfigGuiTrampoline} rather than the settings screen itself.
 */
public final class ForgeConfigGuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return ForgeConfigGuiTrampoline.class;
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
