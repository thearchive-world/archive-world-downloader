// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

public final class FabricPlatformBridge extends AbstractPlatformBridge {
    private static @Nullable BiFunction<Screen, List<AbstractWidget>, List<AbstractWidget>> pauseMenuRowFactory;

    @Override
    protected void registerKeybind(String keyId, Runnable onPress) {
        KeyMapping key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                keyId, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, WdlKeyBinds.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.consumeClick()) {
                onPress.run();
            }
        });
    }

    @Override
    public void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig) {
        pauseMenuRowFactory = (screen, buttons) -> buildPauseMenuRow(screen, buttons, primaryLabelKey, primaryEnabled,
                onPrimary, onConfig);
    }

    /**
     * The wdl pause-menu row for {@code PauseScreenMixin} to add at the tail of {@code PauseScreen.init()}, built from
     * the callbacks {@link #addPauseMenuButtons} stored. Empty before that runs, and in the user's own local world,
     * where the row is hidden.
     */
    public static List<AbstractWidget> pauseMenuRow(Screen screen, List<AbstractWidget> existingButtons) {
        BiFunction<Screen, List<AbstractWidget>, List<AbstractWidget>> factory = pauseMenuRowFactory;
        return factory == null ? Collections.emptyList() : factory.apply(screen, existingButtons);
    }

    @Override
    public void onClientTickEnd(Runnable callback) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> callback.run());
    }

    @Override
    public void onDisconnect(Runnable callback) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> callback.run());
    }

    @Override
    public void onServerJoin(Runnable callback) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> callback.run());
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public String loaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer("wdl")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean observesSpectatorBlockClick() {
        return false;
    }

    /**
     * True. {@code UseEntityCallback} is invoked as the first statement of a mixin, whose handler body carries no
     * gamemode test, and vanilla reaches that call site for a spectator.
     */
    @Override
    public boolean observesSpectatorEntityClick() {
        return true;
    }

    @Override
    public void registerCommands(WdlCommands commands) {
        // No-op on this band: the Fabric client-command API (ClientCommandManager) postdates 1.15.
    }
}
