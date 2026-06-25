// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import java.nio.file.Path;

import world.thearchive.wdl.core.ChatCopy;

/**
 * Loader-services SPI: the thin seam between the loader-agnostic mod and a concrete mod loader. One implementation per
 * loader (Fabric, NeoForge), constructed by that loader's entrypoint and handed to {@code Wdl.initialize} (the loader
 * is known there, so the bridge needs no {@link java.util.ServiceLoader} lookup).
 *
 * <p>Deliberately free of {@code net.minecraft.*} in its signatures so the core can depend on it.
 */
public interface PlatformBridge {
    /** Run {@code callback} at the end of every client tick (client main thread). */
    void onClientTickEnd(Runnable callback);

    /** Run {@code callback} when the client disconnects from a world/server. */
    void onDisconnect(Runnable callback);

    /** Run {@code callback} when the client finishes joining a world/server (the mirror of onDisconnect). */
    void onServerJoin(Runnable callback);

    /** The loader's config directory. */
    Path configDirectory();

    /**
     * Whether the world the client is attached to is served from elsewhere rather than being the user's own local
     * world. A genuine singleplayer world and a LAN-hosted world are the user's own and are excluded.
     */
    boolean isRemoteWorld();

    /** The mod loader's name (for example "Fabric" or "NeoForge"), so a download records which loader ran it. */
    String loaderName();

    /** The mod loader's version, or "unknown" if it cannot be determined. */
    String loaderVersion();

    /** The running mod's own version (modid {@code "wdl"}), for the download report's Software section. */
    String modVersion();

    /** Whether a mod with the given id is loaded. */
    boolean isModLoaded(String modId);

    /**
     * Surface a composed persistent chat line: each segment renders its tint or the vanilla default, and a clickable
     * segment opens its target (URL in the OS browser, file or folder in the OS file browser) with the raw target as
     * its hover.
     */
    void sendChat(ChatCopy line);
}
