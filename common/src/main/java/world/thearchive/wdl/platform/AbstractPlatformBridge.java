// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.platform;

import net.minecraft.client.Minecraft;

/**
 * The loader-agnostic half of {@link PlatformBridge}: everything a loader implementation would otherwise duplicate
 * because it is answered by the vanilla client rather than by the loader. Each loader subclass supplies only what its
 * own API knows.
 */
public abstract class AbstractPlatformBridge implements PlatformBridge {
    @Override
    public boolean isRemoteWorld() {
        Minecraft mc = Minecraft.getInstance();
        // getConnection() is null until Minecraft.player is assigned, which is what keeps this false during
        // the world-load window where the client is already ticking.
        if (mc.getConnection() == null) {
            return false;
        }
        return !mc.isLocalServer();
    }
}
