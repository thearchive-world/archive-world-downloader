// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.client.WdlKeyBinds;
import world.thearchive.wdl.platform.AbstractPlatformBridge;

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
}
