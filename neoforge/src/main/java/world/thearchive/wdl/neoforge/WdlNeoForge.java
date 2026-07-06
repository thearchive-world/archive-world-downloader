// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import world.thearchive.wdl.Wdl;

/**
 * NeoForge client entrypoint, the analog of {@code WdlFabricClient#onInitializeClient}. The {@code @Mod} constructor
 * fires after all mod constructors and is where the loader-agnostic {@link Wdl#initialize} is handed this loader's
 * {@link NeoForgePlatformBridge}.
 *
 * <p>It also installs the connection packet tee on each play connection, the analog of the Fabric
 * {@code ClientPlayConnectionEvents.JOIN} hook. The tee is always installed (entity packet capture is the default
 * mechanism) and no-ops while no download is running.
 */
@Mod(value = "wdl", dist = Dist.CLIENT)
public final class WdlNeoForge {
    public WdlNeoForge(IEventBus modEventBus) {
        Wdl.initialize(new NeoForgePlatformBridge(modEventBus));
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> NeoForgeConnectionTee.install(event.getConnection()));
    }
}
