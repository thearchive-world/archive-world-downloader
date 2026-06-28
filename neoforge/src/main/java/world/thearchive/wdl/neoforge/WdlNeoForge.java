// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import world.thearchive.wdl.Wdl;

/**
 * NeoForge client entrypoint, the analog of {@code WdlFabricClient#onInitializeClient}. The {@code @Mod} constructor
 * fires after all mod constructors and is where the loader-agnostic {@link Wdl#initialize} is handed this loader's
 * {@link NeoForgePlatformBridge}.
 */
@Mod(value = "wdl", dist = Dist.CLIENT)
public final class WdlNeoForge {
    public WdlNeoForge(IEventBus modEventBus) {
        Wdl.initialize(new NeoForgePlatformBridge(modEventBus));
    }
}
