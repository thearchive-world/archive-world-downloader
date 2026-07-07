// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;

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
        MountMenuReader.install(new NeoForgeMountMenuReader());
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> NeoForgeConnectionTee.install(event.getConnection()));
        // Observe the local player's right-clicks for interaction-prediction capture and the open-container
        // bind (which seeds from the clicked block, not the drifting crosshair), on the game bus beside the
        // connection listener. Never cancels the interaction, and no-ops outside a download (unpublished).
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            InteractionCapture.dispatchUseBlock(
                    event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            OpenClickTracker.dispatchUseBlock(event.getEntity(), event.getLevel(), event.getHitVec());
        });
        // Observe entity right-clicks so a chest minecart/boat or chested animal opened by aiming at it binds to
        // the clicked entity rather than the drifting crosshair. No-ops outside a download.
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getEntity(), event.getLevel(), event.getTarget()));
    }
}
