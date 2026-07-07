// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;

/**
 * Fabric client entrypoint: hands {@link Wdl#initialize} the Fabric {@link FabricPlatformBridge} and installs the
 * connection packet tee on each play connection. The tee is always installed (entity packet capture is the default
 * mechanism) and no-ops whenever no download is running.
 */
public final class WdlFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wdl.initialize(new FabricPlatformBridge());
        MountMenuReader.install(new FabricMountMenuReader());
        ClientPlayConnectionEvents.JOIN
                .register((handler, sender, client) -> FabricConnectionTee.install(handler.getConnection()));
        // Observe the local player's right-clicks for interaction-prediction capture and the open-container
        // bind (which seeds from the clicked block, not the drifting crosshair). Always PASS: the hooks never
        // consume the interaction, and they no-op outside a download (the recognizers are unpublished).
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            InteractionCapture.dispatchUseBlock(player, level, hand, hitResult);
            OpenClickTracker.dispatchUseBlock(player, level, hitResult);
            return InteractionResult.PASS;
        });
        // Observe entity right-clicks so a chest minecart/boat or chested animal opened by aiming at it binds to
        // the clicked entity rather than the drifting crosshair. Always PASS; no-ops outside a download.
        UseEntityCallback.EVENT.register((player, level, hand, entity, entityHitResult) -> {
            OpenClickTracker.dispatchUseEntity(player, level, entity);
            return InteractionResult.PASS;
        });
    }
}
