// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

/**
 * Fabric client entrypoint: hands {@link Wdl#initialize} the Fabric {@link FabricPlatformBridge}, installs the
 * connection packet tee on each play connection, and registers the capture HUD overlay plus its peek keybind. The HUD
 * element draws after every vanilla element rather than anchoring to one, so it inherits no gamemode render condition
 * and stays visible in creative and spectator; the render body is shared. The tee is always installed (entity packet
 * capture is the default mechanism) and no-ops whenever no download is running.
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

        KeyMapping peekKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY));
        WdlHudOverlay.bindPeekKey(peekKey);
        // addLast inherits no vanilla render condition. Anchoring relative to a vanilla element would adopt its
        // condition, and those are gamemode-gated (the experience bar is hidden in creative, the hotbar in
        // spectator), so the overlay would vanish there. Drawing last keeps it visible in every gamemode; the
        // overlay self-gates F1 and blocking screens itself.
        HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath("wdl", "hud"), WdlHudOverlay::render);
        new FabricOutlineRegistrar().register();
    }
}
