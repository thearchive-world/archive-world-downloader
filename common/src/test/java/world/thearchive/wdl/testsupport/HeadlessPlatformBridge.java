// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.nio.file.Path;

import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.core.ToastCopy;
import world.thearchive.wdl.platform.PlatformBridge;
import world.thearchive.wdl.platform.WdlCommands;

/**
 * A {@link PlatformBridge} that registers nothing and surfaces nothing, for headless tests that need a bridge only
 * because the type under test takes one. Every callback registration is dropped rather than stored: a test whose
 * subject fires one is asserting through the wrong seam, and the player-facing arms throw so a chat or toast reaching a
 * unit test fails loudly instead of passing silently.
 *
 * <p>Not final only so a test whose subject IS the surfacing can override those two arms. Overriding them to quiet an
 * unexpected chat defeats the point of the throw.
 */
public class HeadlessPlatformBridge implements PlatformBridge {
    private final Path configDirectory;

    public HeadlessPlatformBridge(Path configDirectory) {
        this.configDirectory = configDirectory;
    }

    @Override
    public void registerToggleKeybind(Runnable onToggle) {}

    @Override
    public void registerDownloadsKeybind(Runnable onOpen) {}

    @Override
    public void onClientTickEnd(Runnable callback) {}

    @Override
    public void onDisconnect(Runnable callback) {}

    @Override
    public void onServerJoin(Runnable callback) {}

    @Override
    public Path configDirectory() {
        return configDirectory;
    }

    @Override
    public boolean isRemoteWorld() {
        return true;
    }

    @Override
    public String loaderName() {
        return "Headless";
    }

    @Override
    public String loaderVersion() {
        return "0.0.0";
    }

    @Override
    public String modVersion() {
        return "0.0.0";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public boolean observesSpectatorBlockClick() {
        // Neither shipped loader answers false to both of these: Fabric is false/true and NeoForge
        // true/false. This pair is the most permissive input the crosshair rule takes, so a headless test
        // that ever reaches resolveOpenTarget would exercise a configuration nothing ships. Nothing does
        // today, because that path needs a live client; make these match a real loader before one can.
        return false;
    }

    @Override
    public boolean observesSpectatorEntityClick() {
        return false;
    }

    @Override
    public void sendChat(ChatCopy line) {
        throw new AssertionError("a headless test surfaced player chat: " + line);
    }

    @Override
    public void sendToast(ToastCopy toast) {
        throw new AssertionError("a headless test surfaced a toast: " + toast);
    }

    @Override
    public void registerCommands(WdlCommands commands) {}
}
