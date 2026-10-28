// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

@Mod(value = "wdl", dist = Dist.CLIENT)
public final class WdlNeoForge {
    public WdlNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        Wdl.initialize(new NeoForgePlatformBridge(modEventBus));
        MountMenuReader.install(new NeoForgeMountMenuReader());
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, modListScreen) -> Wdl.createSettingsScreen(modListScreen));
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingIn event) -> NeoForgeConnectionTee.install(event.getConnection()));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            InteractionCapture.dispatchUseBlock(
                    event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            OpenClickTracker.dispatchUseBlock(event.getEntity(), event.getLevel(), event.getHitVec());
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getEntity(), event.getLevel(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping("key.wdl.peek_hud", InputConstants.KEY_LALT, WdlKeyBinds.CATEGORY);
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(peekKey));
        WdlHudOverlay.bindPeekKey(peekKey);
        modEventBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAboveAll(
                Identifier.fromNamespaceAndPath("wdl", "hud"), WdlHudOverlay::render));
        new NeoForgeOutlineRegistrar().register();
    }
}
