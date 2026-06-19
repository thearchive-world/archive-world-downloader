// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import world.thearchive.wdl.platform.AbstractPlatformBridge;

/** The NeoForge half of the loader seam: NeoForge event bus and {@link ModList} metadata, nothing else. */
final class NeoForgePlatformBridge extends AbstractPlatformBridge {
    @Override
    public void onClientTickEnd(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> callback.run());
    }

    @Override
    public void onDisconnect(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> callback.run());
    }

    @Override
    public void onServerJoin(Runnable callback) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> callback.run());
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String loaderVersion() {
        try {
            return ModList.get().getModContainerById("neoforge")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public String modVersion() {
        try {
            return ModList.get().getModContainerById("wdl")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
