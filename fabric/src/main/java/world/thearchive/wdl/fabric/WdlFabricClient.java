// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

public final class WdlFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Wdl.initialize(new FabricPlatformBridge());
        MountMenuReader.install(new FabricMountMenuReader());
        ClientPlayConnectionEvents.JOIN
                .register((handler, sender, client) -> FabricConnectionTee.install(handler.getConnection()));
        // Always PASS: the hooks never consume the interaction.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            InteractionCapture.dispatchUseBlock(player, level, hand, hitResult);
            OpenClickTracker.dispatchUseBlock(player, level, hitResult);
            return InteractionResult.PASS;
        });
        // Always PASS.
        UseEntityCallback.EVENT.register((player, level, hand, entity, entityHitResult) -> {
            OpenClickTracker.dispatchUseEntity(player, level, entity);
            return InteractionResult.PASS;
        });

        KeyMapping peekKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.wdl.peek_hud", InputConstants.KEY_LALT, WdlKeyBinds.CATEGORY));
        WdlHudOverlay.bindPeekKey(peekKey);
        // Anchoring relative to a vanilla element would adopt its condition, and some are gamemode-gated (the hotbar is
        // hidden in spectator), so the overlay would vanish there.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("wdl", "hud"), WdlHudOverlay::render);
        new FabricOutlineRegistrar().register();
    }
}
