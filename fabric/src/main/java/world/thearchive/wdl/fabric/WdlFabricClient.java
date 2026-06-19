// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.api.ClientModInitializer;

import world.thearchive.wdl.Wdl;

/** Fabric client entrypoint: hands {@link Wdl#initialize} the Fabric {@link FabricPlatformBridge}. */
public final class WdlFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wdl.initialize(new FabricPlatformBridge());
    }
}
