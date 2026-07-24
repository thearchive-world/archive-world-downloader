// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/** The Fabric half of the loader seam: Fabric API events and {@link FabricLoader} metadata, nothing else. */
public final class FabricPlatformBridge extends AbstractPlatformBridge {
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

    /**
     * False. The mixin backing {@code UseBlockCallback} is injected into {@code MultiPlayerGameMode.useItemOn} above
     * vanilla's own spectator return, but its handler body opens with its own spectator return, ahead of the invoker
     * call, and says so ("vanilla spectator check happens later, repeat it before the event to avoid false
     * invocations"), so no listener runs. The injection site is not what decides this; the handler body is.
     */
    @Override
    public boolean observesSpectatorBlockClick() {
        return false;
    }

    /**
     * True, the opposite of the block axis. {@code UseEntityCallback} is invoked as the first statement of a mixin on
     * {@code Minecraft.startUseItem}, whose handler body carries no gamemode test, and vanilla reaches that call site
     * for a spectator.
     */
    @Override
    public boolean observesSpectatorEntityClick() {
        return true;
    }

    @Override
    public void registerCommands(WdlCommands commands) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                wdlCommandTree(commands, ClientCommandManager::literal, ClientCommandManager::argument)));
    }
}
