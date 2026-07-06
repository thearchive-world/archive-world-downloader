// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import world.thearchive.wdl.Wdl;

/**
 * Fabric client entrypoint: hands {@link Wdl#initialize} the Fabric {@link FabricPlatformBridge} and installs the
 * connection packet tee on each play connection. The tee is always installed (entity packet capture is the default
 * mechanism) and no-ops whenever no download is running.
 */
public final class WdlFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wdl.initialize(new FabricPlatformBridge());
        ClientPlayConnectionEvents.JOIN
                .register((handler, sender, client) -> FabricConnectionTee.install(handler.getConnection()));
    }
}
