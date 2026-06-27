// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.testsupport;

import java.nio.file.Path;

import world.thearchive.wdl.core.ChatCopy;
import world.thearchive.wdl.platform.PlatformBridge;

/**
 * A {@link PlatformBridge} that registers nothing and surfaces nothing, for headless tests that need a bridge only
 * because the type under test takes one. Every callback registration is dropped rather than stored: a test whose
 * subject fires one is asserting through the wrong seam, and the player-facing arm throws so a chat reaching a unit
 * test fails loudly instead of passing silently.
 *
 * <p>Not final only so a test whose subject IS the surfacing can override that arm. Overriding it to quiet an
 * unexpected chat defeats the point of the throw.
 */
public class HeadlessPlatformBridge implements PlatformBridge {
    private final Path configDirectory;

    public HeadlessPlatformBridge(Path configDirectory) {
        this.configDirectory = configDirectory;
    }

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
    public void sendChat(ChatCopy line) {
        throw new AssertionError("a headless test surfaced player chat: " + line);
    }
}
