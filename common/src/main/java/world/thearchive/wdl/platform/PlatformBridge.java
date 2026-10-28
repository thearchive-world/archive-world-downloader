// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;

public interface PlatformBridge {
    /** Register the capture-toggle keybind; {@code onToggle} runs on each press (client main thread). */
    void registerToggleKeybind(Runnable onToggle);

    /** Register the open-downloads-screen keybind (default unbound); {@code onOpen} runs on each press. */
    void registerDownloadsKeybind(Runnable onOpen);

    /**
     * Add the wdl row to the vanilla pause menu: a primary button that reflects capture state, plus a small "..."
     * button that runs {@code onConfig}. The loader reads {@code primaryLabelKey} and {@code primaryEnabled} only when
     * it builds the row (the label is a {@code wdl} translation key it resolves), and binds a press to
     * {@code onPrimary}, a state-reading dispatch read again at click so a stale label cannot fire the wrong action.
     */
    void addPauseMenuButtons(Supplier<String> primaryLabelKey, BooleanSupplier primaryEnabled,
            Runnable onPrimary, Runnable onConfig);

    /** Run {@code callback} at the end of every client tick (client main thread). */
    void onClientTickEnd(Runnable callback);

    /** Run {@code callback} when the client disconnects from a world/server. */
    void onDisconnect(Runnable callback);

    /** Run {@code callback} when the client finishes joining a world/server. */
    void onServerJoin(Runnable callback);

    /**
     * Run {@code callback} as the client starts tearing its level down, which is the last moment the local player is
     * still live. Separate from {@link #onDisconnect}, which a loader may detect on a client-tick edge that is already
     * past it and so too late to read anything off the player. Fires on every teardown, a dimension change included, so
     * a subscriber that means to end a download here must first ask {@link #isConnectionClosed()} which kind of
     * teardown this is.
     */
    void onLevelTeardown(Runnable callback);

    /**
     * Whether the play connection to the server is gone: its channel closed, or no connection at all. Vanilla decides
     * between processing packets and running its own disconnect teardown on exactly this, so at the
     * {@link #onLevelTeardown} edge it separates a real disconnect, where the channel is already closed on the player's
     * own Disconnect button, from a dimension change.
     */
    boolean isConnectionClosed();

    /** The loader's config directory. */
    Path configDirectory();

    /**
     * Whether the world the client is attached to is served from elsewhere rather than being the user's own local
     * world: a real multiplayer server, or a replay of one. A genuine singleplayer world and a LAN-hosted world are the
     * user's own and are excluded.
     */
    boolean isRemoteWorld();

    /**
     * Whether a screen is open. The chat screen does not count: it leaves the world and HUD visible, so drawing over
     * the world stays valid while it is up.
     */
    boolean isBlockingScreenOpen();

    /** Whether the vanilla HUD is hidden by the F1 hide-GUI toggle. */
    boolean isHudHidden();

    /** The mod loader's name. */
    String loaderName();

    /** The mod loader's version, or "unknown" if it cannot be determined. */
    String loaderVersion();

    /** The running mod's own version (modid {@code "wdl"}). */
    String modVersion();

    /** Whether a mod with the given id is loaded. */
    boolean isModLoaded(String modId);

    /**
     * Whether this loader's block use hook observes a right-click a SPECTATOR made. The crosshair fallback exists only
     * to cover the axis the loader is blind on, so on a loader that observes this axis the block leg would fire for
     * nothing but opens the click chain did not account for, where a crosshair is a guess and not provenance.
     */
    boolean observesSpectatorBlockClick();

    /** Whether this loader's entity use hook observes a right-click a SPECTATOR made. */
    boolean observesSpectatorEntityClick();

    /**
     * Surface a composed persistent chat line: a clickable segment opens its target (URL in the OS browser, folder in
     * the OS file browser) with the raw target as its hover.
     */
    void sendChat(ChatCopy line);

    void sendToast(ToastCopy toast);

    void registerCommands(WdlCommands commands);
}
