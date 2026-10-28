// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import world.thearchive.wdl.Wdl;
import world.thearchive.wdl.adapter.InteractionCapture;
import world.thearchive.wdl.adapter.MountMenuReader;
import world.thearchive.wdl.adapter.OpenClickTracker;
import world.thearchive.wdl.client.WdlHudOverlay;
import world.thearchive.wdl.client.WdlKeyBinds;

@Mod("wdl")
public final class WdlForge {
    public WdlForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        Wdl.initialize(new ForgePlatformBridge(modEventBus));
        MountMenuReader.install(new ForgeMountMenuReader());
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                () -> (minecraft, modListScreen) -> Wdl.createSettingsScreen(modListScreen));
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggedInEvent event) -> ForgeConnectionTee.install(event.getNetworkManager()));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            InteractionCapture.dispatchUseBlock(
                    event.getPlayer(), event.getWorld(), event.getHand(), event.getHitVec());
            OpenClickTracker.dispatchUseBlock(event.getPlayer(), event.getWorld(), event.getHitVec());
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> OpenClickTracker
                .dispatchUseEntity(event.getPlayer(), event.getWorld(), event.getTarget()));

        KeyMapping peekKey = new KeyMapping(
                "key.wdl.peek_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, WdlKeyBinds.CATEGORY);
        modEventBus.addListener(
                (FMLClientSetupEvent event) -> event.enqueueWork(() -> ClientRegistry.registerKeyBinding(peekKey)));
        WdlHudOverlay.bindPeekKey(peekKey);
        // Post with ElementType.ALL fires once after the whole vanilla HUD, so the overlay draws above every element in
        // every gamemode.
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post event) -> {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                WdlHudOverlay.render(event.getMatrixStack(), event.getPartialTicks());
            }
        });
        new ForgeOutlineRegistrar().register();
    }
}
