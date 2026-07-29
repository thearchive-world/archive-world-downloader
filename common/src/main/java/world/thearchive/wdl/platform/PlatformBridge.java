// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;

/**
 * Loader-services SPI: the thin seam between the loader-agnostic mod and a concrete mod loader. One implementation per
 * loader (Fabric, NeoForge), constructed by that loader's entrypoint and handed to {@code Wdl.initialize} (the loader
 * is known there, so unlike the per-band {@code VersionAdapter} the bridge needs no {@link java.util.ServiceLoader}
 * lookup).
 *
 * <p>Deliberately free of {@code net.minecraft.*} in its signatures so the core can depend on it.
 */
public interface PlatformBridge {
    /** Register the capture-toggle keybind; {@code onToggle} runs on each press (client main thread). */
    void registerToggleKeybind(Runnable onToggle);

    /** Register the open-downloads-screen keybind (default unbound); {@code onOpen} runs on each press. */
    void registerDownloadsKeybind(Runnable onOpen);

    /**
     * Add the wdl row to the vanilla pause menu, wired mixin-free through the loader's public screen-init event: a
     * primary button that reflects capture state, plus a small "..." button that runs {@code onConfig}. The loader
     * reads {@code primaryLabelKey} and {@code primaryEnabled} once when the pause menu opens (the label is a
     * {@code wdl} translation key it resolves to a {@code Component}, so the seam stays free of
     * {@code net.minecraft.*}), and binds a press to {@code onPrimary}, a state-reading dispatch read again at click so
     * a stale label cannot fire the wrong action.
     */
    void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig);

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
     * world: a real multiplayer server, or a replay of one. A genuine singleplayer world and a LAN-hosted world are the
     * user's own and are excluded.
     */
    boolean isRemoteWorld();

    /** The mod loader's name (for example "Fabric" or "NeoForge"), so a download records which loader ran it. */
    String loaderName();

    /** The mod loader's version, or "unknown" if it cannot be determined. */
    String loaderVersion();

    /** The running mod's own version (modid {@code "wdl"}), for the download report's Software section. */
    String modVersion();

    /** Whether a mod with the given id is loaded, gating the optional coverage overlay integration. */
    boolean isModLoaded(String modId);

    /**
     * Whether this loader's block use hook observes a right-click a SPECTATOR made. Vanilla is not what decides this:
     * the client sends the use packet and the server opens the menu for any clicked block carrying a
     * {@code MenuProvider} in its SPECTATOR branch, so a spectator's block open is real on every loader. What differs
     * is whether the loader hands the observation over, and the two loaders differ on it in opposite directions from
     * {@link #observesSpectatorEntityClick}. Read by the open-container bind: the crosshair fallback exists only to
     * cover the axis the loader is blind on, so on a loader that observes this axis the block leg would fire for
     * nothing but opens the click chain did not account for, where a crosshair is a guess and not provenance.
     */
    boolean observesSpectatorBlockClick();

    /** Whether this loader's entity use hook observes a right-click a SPECTATOR made; see the block sibling. */
    boolean observesSpectatorEntityClick();

    /**
     * Surface a composed persistent chat line: each segment renders its tint or the vanilla default, and a clickable
     * segment opens its target (URL in the OS browser, file or folder in the OS file browser) with the raw target as
     * its hover.
     */
    void sendChat(ChatCopy line);

    /** Enqueue a job-done toast on the vanilla system-toast tray (client main thread). */
    void sendToast(ToastCopy toast);

    /**
     * Register the {@code /wdl} client command; the loader builds the Brigadier tree and dispatches to
     * {@code commands}' actions on the client main thread.
     */
    void registerCommands(WdlCommands commands);
}
